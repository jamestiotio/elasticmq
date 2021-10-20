package org.elasticmq.server

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import org.elasticmq.ElasticMQError
import org.elasticmq.actor.QueueManagerActor
import org.elasticmq.actor.queue.QueueEvent
import org.elasticmq.actor.reply._
import org.elasticmq.persistence.file.ConfigBasedQueuePersistenceActor
import org.elasticmq.persistence.sql.SqlQueuePersistenceActor
import org.elasticmq.rest.sqs.{SQSRestServer, TheSQSRestServerBuilder}
import org.elasticmq.rest.stats.{StatisticsRestServer, TheStatisticsRestServerBuilder}
import org.elasticmq.server.config.ElasticMQServerConfig
import org.elasticmq.util.{Logging, NowProvider}
import scala.concurrent.duration._

import scala.concurrent.duration.Duration.Inf
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

class ElasticMQServer(config: ElasticMQServerConfig) extends Logging {
  val actorSystem = ActorSystem("elasticmq")

  implicit val timeout: Timeout = Timeout(5.seconds)

  def start() = {
    val queueConfigStore: Option[ActorRef] = createQueueEventListener
    val queueManagerActor = createBase(queueConfigStore)
    val restServerOpt = optionallyStartRestSqs(queueManagerActor, queueConfigStore)
    val restStatisticsServerOpt = optionallyStartRestStatistics(queueManagerActor)

    val shutdown = () => {
      implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher

      val futureTerminationRestSQS = restServerOpt.map(_.stopAndGetFuture()).getOrElse(Future.unit)
      val futureTerminationRestStats = restStatisticsServerOpt.map(_.stopAndGetFuture()).getOrElse(Future.unit)
      val eventualTerminated = for {
        _ <- futureTerminationRestSQS
        _ <- futureTerminationRestStats
        ac <- actorSystem.terminate()
      } yield ac
      Await.result(eventualTerminated, Inf)

    }

    val logErrorsAndShutdown = { errors: List[ElasticMQError] =>
      errors.foreach(error => logger.error(s"Could not start server because $error"))
      shutdown()
    }

    queueConfigStore match {
      case Some(queueConfigStoreActor) =>
        restoreQueues(queueConfigStoreActor, queueManagerActor) match {
          case Some(errors) => logErrorsAndShutdown(errors)
          case None =>
        }
      case None =>
        createQueues(queueManagerActor) match {
          case Some(errors) => logErrorsAndShutdown(errors)
          case None =>
        }
    }

    shutdown
  }

  private def createQueueEventListener: Option[ActorRef] =
    if (config.sqlQueuePersistenceConfig.enabled) {
      Some(
        actorSystem.actorOf(Props(new SqlQueuePersistenceActor(config.sqlQueuePersistenceConfig, config.baseQueues)))
      )
    } else if (config.queuesStorageEnabled) {
      Some(
        actorSystem.actorOf(Props(new ConfigBasedQueuePersistenceActor(config.queuesStoragePath, config.baseQueues)))
      )
    } else None

  private def createBase(queueConfigStore: Option[ActorRef]): ActorRef =
    actorSystem.actorOf(Props(new QueueManagerActor(new NowProvider(), config.restSqs.sqsLimits, queueConfigStore)))

  private def optionallyStartRestSqs(queueManagerActor: ActorRef, queueConfigStore: Option[ActorRef]): Option[SQSRestServer] = {
    if (config.restSqs.enabled) {

      val server = TheSQSRestServerBuilder(
        Some(actorSystem),
        Some(queueManagerActor),
        config.restSqs.bindHostname,
        config.restSqs.bindPort,
        config.nodeAddress,
        config.generateNodeAddress,
        config.restSqs.sqsLimits,
        config.awsRegion,
        config.awsAccountId,
        queueConfigStore
      ).start()

      server.waitUntilStarted()

      Some(server)
    } else {
      None
    }
  }

  private def optionallyStartRestStatistics(queueManagerActor: ActorRef): Option[StatisticsRestServer] = {
    if (config.restStatisticsConfiguration.enabled) {

      val server = TheStatisticsRestServerBuilder(
        actorSystem,
        queueManagerActor,
        config.restStatisticsConfiguration.bindHostname,
        config.restStatisticsConfiguration.bindPort,
        config.awsRegion,
        config.awsAccountId
      ).start()

      server.waitUntilStarted()

      Some(server)
    } else {
      None
    }
  }

  private def restoreQueues(queueConfigStore: ActorRef, queueManagerActor: ActorRef): Option[List[ElasticMQError]] =
    Await.result(queueConfigStore ? QueueEvent.Restore(queueManagerActor), timeout.duration).swap.toOption

  private def createQueues(queueManagerActor: ActorRef): Option[List[ElasticMQError]] = {
    val errors = config
      .baseQueues
      .flatMap(createQueue =>
        Await
          .result(queueManagerActor ? org.elasticmq.msg.CreateQueue(createQueue.toQueueData), timeout.duration)
          .swap
          .toOption
      )

    if (errors.nonEmpty) Some(errors) else None
  }
}
