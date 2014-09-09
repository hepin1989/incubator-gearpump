/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gearpump.cluster

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.contrib.datareplication.{GSet, DataReplication}
import akka.contrib.datareplication.Replicator._
import org.apache.gearpump.cluster.AppMasterToMaster._
import org.apache.gearpump.cluster.AppMasterToWorker._
import org.apache.gearpump.cluster.ClientToMaster._
import org.apache.gearpump.cluster.MasterToAppMaster._
import org.apache.gearpump.cluster.MasterToClient.{ShutdownApplicationResult, SubmitApplicationResult}
import org.apache.gearpump.cluster.WorkerToAppMaster._
import org.apache.gearpump.util.ActorSystemBooter.{BindLifeCycle, CreateActor, RegisterActorSystem}
import org.apache.gearpump.util.{ActorSystemBooter, ActorUtil, Util}
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props, _}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.contrib.datareplication.Replicator._
import akka.contrib.pattern.ClusterSingletonManager
import com.typesafe.config.ConfigFactory

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._

import scala.concurrent.duration._
import akka.actor.ActorLogging
import akka.cluster.Cluster
import akka.actor.Actor
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import akka.contrib.datareplication.{GSet, DataReplication}
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
/**
 * AppManager is dedicated part of Master to manager applicaitons
 */

class ApplicationState(val appId : Int, val attemptId : Int, val state : Any) extends Serializable {

  override def equals(other: Any): Boolean = {
    if (other.isInstanceOf[ApplicationState]) {
      val that = other.asInstanceOf[ApplicationState]
      if (appId == that.appId && attemptId == that.attemptId) {
        return true
      } else {
        return false
      }
    } else {
      return false
    }
  }

  override def hashCode: Int = {
    import akka.routing.MurmurHash._
    extendHash(appId, attemptId, startMagicA, startMagicB)
  }
}

private[cluster] class AppManager() extends Actor with Stash {
  import org.apache.gearpump.cluster.AppManager._

  private var master : ActorRef = null
  private var executorCount : Int = 0;
  private var appId : Int = 0;

  private val systemconfig = context.system.settings.config

  def receive : Receive = null

  //from appid to appMaster data
  private var appMasterRegistry = Map.empty[Int, AppMasterInfo]

  private val STATE = "masterstate"
  private val TIMEOUT = Duration(5, TimeUnit.SECONDS)
  private val replicator = DataReplication(context.system).replicator

  //TODO: We can use this state for appmaster HA to recover a new App master
  private var state : Set[ApplicationState] = Set.empty[ApplicationState]

  val masterClusterSize = systemconfig.getStringList("gearpump.cluster.masters").size()

  //optimize write path, we can tollerate one master down for recovery.
  val writeQuorum = Math.min(2, masterClusterSize / 2 + 1)
  val readQuorum = masterClusterSize + 1 - writeQuorum

  replicator ! new Get(STATE, ReadFrom(readQuorum), TIMEOUT, None)
  LOG.info("Recoving application state....")
  context.become(waitForMasterState)

  def waitForMasterState : Receive = {
    case GetSuccess(_, replicatedState : GSet, _) =>
      state = replicatedState.getValue().asScala.foldLeft(state) { (set, appState) =>
        set + appState.asInstanceOf[ApplicationState]
      }
      appId = state.map(_.appId).size
      LOG.info(s"Successfully recoeved application states for ${state.map(_.appId)}, nextAppId: ${appId}....")
      context.become(receiveHandler)
      unstashAll()
    case x : GetFailure =>
      LOG.info("GetFailure We cannot find any exisitng state, start a fresh one...")
      context.become(receiveHandler)
      unstashAll()
    case x : NotFound =>
      LOG.info("We cannot find any exisitng state, start a fresh one...")
      context.become(receiveHandler)
      unstashAll()
    case msg =>
      LOG.info(s"Get information ${msg.getClass.getSimpleName}")
      stash()
  }

  def receiveHandler = appMasterMessage orElse appMasterMessage orElse terminationWatch

  def clientMsgHandler : Receive = {
    case submitApp @ SubmitApplication(appMasterClass, config, app) =>
      LOG.info(s"AppManager Submiting Application $appId...")
      val appWatcher = context.actorOf(Props(classOf[AppMasterWatcher], appId, appMasterClass, config, app), appId.toString)
      replicator ! Update(STATE, GSet(), WriteTo(writeQuorum), TIMEOUT)(_ + new ApplicationState(appId, 0, submitApp))
      sender.tell(SubmitApplicationResult(Success(appId)), context.parent)
      appId += 1
    case ShutdownApplication(appId) =>
      LOG.info(s"App Manager Shutting down application $appId")
      val child = context.child(appId.toString)
      if (child.isEmpty) {
        sender.tell(ShutdownApplicationResult(Failure(new Exception(s"App $appId not found"))), context.parent)
      } else {
        //TODO: find worker, executorId,

        val data = appMasterRegistry.get(appId)
        if (data.isDefined) {
          val worker = data.get.worker
          LOG.info(s"Shuttdown app master at ${worker.path.toString}, appId: $appId, executorId: $masterExecutorId")
          worker ! ShutdownExecutor(appId, masterExecutorId, s"AppMaster $appId shutdown requested by master...")
          sender ! ShutdownApplicationResult(Success(appId))
        }
        else {
          val errorMsg = s"Find to find regisration information for appId: $appId"
          LOG.error(errorMsg)
          sender ! ShutdownApplicationResult(Failure(new Exception(errorMsg)))
        }
      }

  }

  def appMasterMessage : Receive = {
    case RegisterAppMaster(master, appId, executorId, slots, registerData : AppMasterInfo) => {
      LOG.info(s"Master $executorId has been launched...")
      context.watch(master)
      appMasterRegistry += appId -> registerData
    }
  }

  def terminationWatch : Receive = {
    //TODO: fix this
    case terminate : Terminated => {
      terminate.getAddressTerminated()
      //TODO: Check whether this belongs to a app master
      LOG.info(s"App Master is terminiated, network down: ${terminate.getAddressTerminated()}")

      //TODO: decide whether it is a normal terminaiton, or abnormal. and implement appMaster HA
    }
  }
}

case class AppMasterInfo(worker : ActorRef) extends AppMasterRegisterData

private[cluster] object AppManager {
  private val masterExecutorId = -1
  private val LOG: Logger = LoggerFactory.getLogger(classOf[AppManager])

  /**
   * Start and watch Single AppMaster's lifecycle
   */
  class AppMasterWatcher(appId : Int, appMasterClass : Class[_ <: Actor], appConfig : Configs, app : Application) extends Actor {

    val master = context.actorSelection("../../")
    master ! RequestResource(appId, 1)
    LOG.info(s"AppManager asking Master for resource for app $appId...")

    def receive : Receive = waitForResourceAllocation

    def waitForResourceAllocation : Receive = {
      case ResourceAllocated(resource) => {
        LOG.info(s"Resource allocated for appMaster $appId")
        val Resource(worker, slots) = resource(0)
        val appMasterConfig = appConfig.withAppId(appId).withAppDescription(app).withMaster(sender).withAppMasterRegisterData(AppMasterInfo(worker)).withExecutorId(masterExecutorId).withSlots(slots)
        LOG.info(s"Try to launch a executor for app Master on $worker for app $appId")
        val name = actorNameForExecutor(appId, masterExecutorId)
        val selfPath = ActorUtil.getFullPath(context)

        val executionContext = ExecutorContext(Util.getCurrentClassPath, context.system.settings.config.getString("gearpump.streaming.appmaster.vmargs").split(" "), classOf[ActorSystemBooter].getName, Array(name, selfPath))

        worker ! LaunchExecutor(appId, masterExecutorId, slots,executionContext)
        context.become(waitForActorSystemToStart(worker, appMasterConfig))
      }
    }

    def waitForActorSystemToStart(worker : ActorRef, masterConfig : Configs) : Receive = {
      case RegisterActorSystem(systemPath) =>
        LOG.info(s"Received RegisterActorSystem $systemPath for app master")
        val masterProps = Props(appMasterClass, masterConfig)

        sender ! CreateActor(masterProps, "appmaster")

        //bind lifecycle with worker
        sender ! BindLifeCycle(worker)
        context.become(waitForAppMasterToStart)
    }

    private def actorNameForExecutor(appId : Int, executorId : Int) = "app" + appId + "-executor" + executorId

    def waitForAppMasterToStart : Receive = {
      case ExecutorLaunched(appId, executorId, slots) => {
        LOG.info("Successfully launched executor on worker, my mission is completed, close myself...")
        context.stop(self)
      }
      case ExecutorLaunchFailed(reason, ex) => {
        LOG.error(s"Executor Launch failed reason：$reason", ex)
        //TODO: restart this process, ask for resources
        //
      }
    }
  }
}