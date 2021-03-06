package election;

import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gash.router.server.ServerState;
import gash.router.server.messages.wrk_messages.LeaderStatusMessage;
import pipe.election.Election.LeaderStatus.LeaderQuery;
import pipe.election.Election.LeaderStatus.LeaderState;

/**
 * @author: codepenman.
 * @date: 3/31/16
 *
 *        This class is mainly for Leader to keep track of all the followers
 *        health status..
 *
 *        This task, when started broad cast heart messages to the follower and
 *        starts listening to the reply.
 *
 *        On reply from follower's I maintain follower2BeatTimeMap which will be
 *        updated with time beat was received mapped to the follower who sent
 *        beat.
 *
 *        Thread will be invoked in frequent intervals of time and checks if
 *        there is any follower who didn't reply, if yes notify FollowerListener
 *        to remove the node. Once the iteration is done, I again send broad
 *        cast messages to all my followers and go back to wait.
 *
 */
public class FollowerHealthMonitor {

	private boolean debug = false;
	private final Logger logger = LoggerFactory.getLogger("Follower Health Monitor");
	private ConcurrentHashMap<Integer, Long> follower2BeatTimeMap;
	private final ServerState state;
	private FollowerListener followerListener;
	private HealthMonitorTask task;
	private AtomicBoolean stop;
	private final long timeout;

	public FollowerHealthMonitor(FollowerListener followerListener, ServerState state,
		long timeout) {
		this.followerListener = followerListener;
		this.state = state;
		this.timeout = timeout;
		this.task = new HealthMonitorTask();
		this.stop = new AtomicBoolean(false);
		this.follower2BeatTimeMap = new ConcurrentHashMap<>();
	}

	public void onBeat(int followerId, long heartBeatTime) {
		follower2BeatTimeMap.put(followerId, heartBeatTime);
	}

	public void start() {
		// Broadcast heartbeat to all the followers
		LeaderStatusMessage beat = new LeaderStatusMessage(state.getConf().getNodeId());
		beat.setElectionId(state.getElectionId());
		beat.setLeaderId(state.getLeaderId());
		beat.setMaxHops(state.getConf().getMaxHops());
		beat.setLeaderAction(LeaderQuery.BEAT);
		beat.setLeaderState(LeaderState.LEADERALIVE);

		state.getEmon().broadcastMessage(beat.getMessage());

		stop.getAndSet(false);
		task.start();
	}

	public void cancel() {
		stop.getAndSet(true);
		task = new HealthMonitorTask();
	}

	private class HealthMonitorTask extends Thread {

		private boolean broadCastBeat = false;

		@Override
		public void run() {
			try {
				
				while (!stop.get()) {

					if (debug)
						logger.info(
							"********Started: " + new Date(System.currentTimeMillis()));

					if (broadCastBeat) {
						// Broadcast heartbeat to all the followers
						logger.info("#####Leader broadcasting heartbeat to all followers");

						LeaderStatusMessage beat = new LeaderStatusMessage(
							state.getConf().getNodeId());
						beat.setElectionId(state.getElectionId());
						beat.setLeaderId(state.getLeaderId());
						beat.setMaxHops(state.getConf().getMaxHops());
						beat.setLeaderAction(LeaderQuery.BEAT);
						beat.setLeaderState(LeaderState.LEADERALIVE);

						state.getEmon().broadcastMessage(beat.getMessage());
						broadCastBeat = false;
						
						synchronized (this) {
							wait((long) (timeout * 0.9));
						}
					} else {
						long currentTime = System.currentTimeMillis();

						ArrayList<Integer> nodes2Remove = new ArrayList<>();

						nodes2Remove.addAll(follower2BeatTimeMap.entrySet().stream()
							.filter(entry -> currentTime - entry.getValue() > timeout)
							.map(Map.Entry::getKey).collect(Collectors.toList()));

						for (Integer nodeId : nodes2Remove) {
							follower2BeatTimeMap.remove(nodeId);
							followerListener.removeFollower(nodeId);
						}

						logger.info("####Follower heartbeats:" + follower2BeatTimeMap);
						broadCastBeat = true;
					}
				}
			} catch (InterruptedException e) {
				logger.info("********Timer was interrupted: "
					+ new Date(System.currentTimeMillis()));
			}
		}
	}
}
