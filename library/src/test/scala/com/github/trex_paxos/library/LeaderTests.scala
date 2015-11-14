package com.github.trex_paxos.library

import TestHelpers._

import scala.collection.immutable.TreeMap
import Ordering._

class LeaderTests extends AllRolesTests with LeaderLikeTests {
  val initialDataAgent = PaxosAgent(0, Leader, initialData)

  object `The Leader Function` {
    def `should be defined for a leader and RetransmitRequest` {
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, initialDataAgent, RetransmitRequest(0, 1, 0L))))
    }

    def `should be defined for a leader and RetransmitResponse` {
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, initialDataAgent, RetransmitResponse(0, 1, Seq(), Seq()))))
    }

    def `should be defined for a leader and a Prepare if the prepare is less than the promise` {
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, initialDataAgent, Prepare(Identifier(0, BallotNumber(Int.MinValue, Int.MinValue), 0)))))
    }

    def `should be defined for a leader and a Prepare if the prepare is higher than the promise` {
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, initialDataAgent, Prepare(Identifier(0, BallotNumber(Int.MaxValue, Int.MaxValue), 0)))))
    }

    def `should be defined for a leader and a Prepare if the prepare is equal to the promise` {
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, initialDataAgent, Prepare(Identifier(0, initialDataAgent.data.progress.highestPromised, 0)))))
    }

    def `should be defined for a leader and an Accept with a lower number` {
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, initialDataAgent, Accept(Identifier(0, BallotNumber(Int.MinValue, Int.MinValue), 0), NoOperationCommandValue))))
    }

    def `should be defined for a leader and an Accept with a higher number for a committed slot` {
      val promise = BallotNumber(Int.MaxValue, Int.MaxValue)
      val initialData = TestHelpers.highestPromisedHighestCommittedLens.set(TestHelpers.initialData, (promise, Identifier(from = 0, number = promise, logIndex = 99L)))
      val higherCommittedAgent = PaxosAgent(0, Leader, initialData)
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, higherCommittedAgent, Accept(Identifier(0, promise, 0), NoOperationCommandValue))))
    }

    def `should be defined for an Accept equal to promise` {
      val promise = BallotNumber(Int.MaxValue, Int.MaxValue)
      val initialData = highestPromisedLens.set(TestHelpers.initialData, promise)
      val equalPromiseAgent = PaxosAgent(0, Leader, initialData)
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, equalPromiseAgent, Accept(Identifier(0, promise, 0), NoOperationCommandValue))))
    }

    def `should be defined for an Accept greater than promise` {
      val higherAcceptId = BallotNumber(Int.MaxValue, Int.MaxValue)
      val lowerPromise = BallotNumber(Int.MaxValue -1 , Int.MaxValue - 1)
      val initialData = highestPromisedLens.set(TestHelpers.initialData, lowerPromise)
      val higherPromiseAgent = PaxosAgent(0, Leader, initialData)
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, higherPromiseAgent, Accept(Identifier(0, higherAcceptId, 0), NoOperationCommandValue))))
    }

    def `should be defined for a Heartbeat` = {
      val agent = PaxosAgent(0, Leader, initialData)
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, agent, HeartBeat)))
    }

    def `should be defined for a CheckTimeout when not timed out` = {
      val agent = PaxosAgent(0, Leader, initialData)
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(negativeClockIO, agent, CheckTimeout)))
    }

    def `should be defined for a CheckTimeout when accepts are timed out` {
      val agent = PaxosAgent(0, Leader, initialData.copy(acceptResponses = emptyAcceptResponses))
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(maxClockIO, agent, CheckTimeout)))
    }

    def `should deal with timed-out prepares before timed-out accepts` {
      var handleResendAcceptsInvoked = false
      var handleResendPreparesInvoked = false
      val paxosAlgorithm = new PaxosAlgorithm {
        override def handleResendAccepts(io: PaxosIO, agent: PaxosAgent, time: Long): PaxosAgent = {
          handleResendAcceptsInvoked = true
          agent
        }
        override def handleResendPrepares(io: PaxosIO, agent: PaxosAgent, time: Long): PaxosAgent = {
          handleResendPreparesInvoked = true
          agent
        }
      }
      val agent = PaxosAgent(0, Leader, initialData.copy(prepareResponses = prepareSelfVotes, acceptResponses = emptyAcceptResponses))
      paxosAlgorithm.leaderFunction(PaxosEvent(maxClockIO, agent, CheckTimeout))
      assert(handleResendPreparesInvoked == true && handleResendAcceptsInvoked == false)
    }

    def `should be defined for a commit at a higher log index` {
      val agent = PaxosAgent(0, Leader, initialData)
      val commit = Commit(Identifier(1, initialData.progress.highestPromised, Int.MaxValue))
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(maxClockIO, agent, commit)))
    }

    def `should be defined for a commit at a same log index with a higher number` {
      val agent = PaxosAgent(0, Leader, initialData)
      val commit = Commit(Identifier(1, BallotNumber(Int.MaxValue, Int.MaxValue), initialData.progress.highestCommitted.logIndex))
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(maxClockIO, agent, commit)))
    }

    def `should be defined for a low commit` {
      val agent = PaxosAgent(0, Leader, initialData)
      val commit = Commit(Identifier(1, BallotNumber(Int.MinValue, Int.MinValue), Long.MinValue))
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(maxClockIO, agent, commit)))
    }

    def `should be defined for Heatbeat`{
      val agent = PaxosAgent(0, Leader, initialData)
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(maxClockIO, agent, HeartBeat)))
    }

    def `should be defined for a client command message` {
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, initialDataAgent, ClientRequestCommandValue(0L, Array.empty[Byte]))))
    }

    def `should be defined for a late PrepareResponse`  {
      val dataPrepareResponses = initialDataAgent.data.copy(prepareResponses = TreeMap(Identifier(0, BallotNumber(Int.MinValue, Int.MinValue), 0) -> Map.empty))
      assert(paxosAlgorithm.leaderFunction.isDefinedAt(PaxosEvent(undefinedIO, initialDataAgent.copy(data = dataPrepareResponses), undefinedPrepareResponse)))
    }

    val leader = PaxosAgent(0, Leader, initialDataAgent.data.copy(
      epoch = Option(BallotNumber(Int.MaxValue, Int.MaxValue)),
      acceptResponses = acceptSelfAck98,
      clientCommands = initialDataClientCommand
    ))

    def `Return to follower handler should do nothing for commit not at higher slot ` = {
      val handler = new ReturnToFollowerHandler with CommitHandler {}
      val commitAtIndex = Commit(leader.data.progress.highestCommitted)
      handler.handleReturnToFollowerOnHigherCommit(undefinedSilentIO, leader, commitAtIndex) match {
        case `leader` => // good
        case f => fail(f.toString)
      }
    }
  }

  object `A Leader` {
    def `should use prepare handler` {
      usesPrepareHandler(Leader)
    }
    def `should ignore a lower commit` {
      shouldIngoreLowerCommit(Leader)
    }
    def `should ingore a commit for same slot from lower numered node` {
      shouldIngoreCommitMessageSameSlotLowerNodeId(Leader)
    }
    def `should backdown on commit same slot higher node number` {
      shouldBackdownOnCommitSameSlotHigherNodeId(Leader)
    }
    def `should backdown on higher slot commit` {
      shouldBackdownOnHigherSlotCommit(Leader)
    }
    def `should backdown and commit on higher slot commit` {
      shouldBackdownAndCommitOnHigherSlotCommit(Leader)
    }
    def `reissue same accept messages it gets a timeout and no challenge` {
      shouldReissueSameAcceptMessageIfTimeoutNoChallenge(Leader)
    }
    def `reissue higher accept messages upon learning of another nodes higher promise in a nack` {
      sendHigherAcceptOnLearningOtherNodeHigherPromise(Leader)
    }
    def `reissues higher accept message upon having made a higher promise itself by the timeout` {
      sendsHigherAcceptOnHavingMadeAHigherPromiseAtTimeout(Leader)
    }
  }

}
