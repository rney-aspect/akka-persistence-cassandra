/*
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.cassandra.query.javadsl

import scala.concurrent.duration._
import akka.actor.{ Props, ActorSystem }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{ ImplicitSender, TestKit }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Seconds, Second, Span }
import org.scalatest.{ Matchers, WordSpecLike }
import akka.persistence.cassandra.CassandraLifecycle
import akka.persistence.cassandra.query.{ TestActor, javadsl, scaladsl }
import akka.persistence.query.PersistenceQuery
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.journal.{ Tagged, WriteEventAdapter }
import akka.stream.testkit.scaladsl.TestSink

object CassandraReadJournalSpec {
  val config = s"""
    akka.loglevel = INFO
    akka.test.single-expect-default = 10s
    akka.persistence.journal.plugin = "cassandra-journal"
    cassandra-journal.port = ${CassandraLauncher.randomPort}
    cassandra-query-journal.max-buffer-size = 10
    cassandra-query-journal.refresh-interval = 0.5s
    cassandra-query-journal.eventual-consistency-delay = 1s
    cassandra-journal.event-adapters {
      test-tagger = akka.persistence.cassandra.query.javadsl.TestTagger
    }
    cassandra-journal.event-adapter-bindings = {
      "java.lang.String" = test-tagger
    }
               """
}

class TestTagger extends WriteEventAdapter {
  override def manifest(event: Any): String = ""
  override def toJournal(event: Any): Any = event match {
    case s: String if s.startsWith("a") => Tagged(event, Set("a"))
    case _                              => event
  }
}

class CassandraReadJournalSpec
  extends TestKit(ActorSystem("JavaCassandraReadJournalSpec", ConfigFactory.parseString(CassandraReadJournalSpec.config)))
  with ScalaFutures
  with ImplicitSender
  with WordSpecLike
  with CassandraLifecycle
  with Matchers {

  override def systemName: String = "JavaCassandraReadJournalSpec"

  implicit val mat = ActorMaterializer()(system)

  implicit val patience = PatienceConfig(Span(10, Seconds), Span(1, Second))

  lazy val javaQueries = PersistenceQuery(system)
    .getReadJournalFor(classOf[javadsl.CassandraReadJournal], scaladsl.CassandraReadJournal.Identifier)

  "Cassandra Read Journal Java API" must {
    "start eventsByPersistenceId query" in {
      val a = system.actorOf(Props(new TestActor("a")))
      a ! "a-1"
      expectMsg("a-1-done")

      val src = javaQueries.eventsByPersistenceId("a", 0L, Long.MaxValue)
      src.asScala.map(_.persistenceId).runWith(TestSink.probe[Any])
        .request(10)
        .expectNext("a")
        .cancel()
    }

    "start current eventsByPersistenceId query" in {
      val a = system.actorOf(Props(new TestActor("b")))
      a ! "b-1"
      expectMsg("b-1-done")

      val src = javaQueries.currentEventsByPersistenceId("b", 0L, Long.MaxValue)
      src.asScala.map(_.persistenceId).runWith(TestSink.probe[Any])
        .request(10)
        .expectNext("b")
        .expectComplete()
    }

    "start eventsByTag query" in {
      val src = javaQueries.eventsByTag("a", 0L)
      src.asScala.map(_.persistenceId).runWith(TestSink.probe[Any])
        .request(10)
        .expectNext("a")
        .expectNoMsg(100.millis)
        .cancel()
    }

    "start current eventsByTag query" in {
      val src = javaQueries.currentEventsByTag("a", 0L)
      src.asScala.map(_.persistenceId).runWith(TestSink.probe[Any])
        .request(10)
        .expectNext("a")
        .expectComplete()
    }
  }
}
