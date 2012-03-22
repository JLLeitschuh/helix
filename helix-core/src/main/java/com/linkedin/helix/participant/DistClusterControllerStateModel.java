package com.linkedin.helix.participant;

import org.apache.log4j.Logger;

import com.linkedin.helix.HelixManager;
import com.linkedin.helix.HelixManagerFactory;
import com.linkedin.helix.InstanceType;
import com.linkedin.helix.NotificationContext;
import com.linkedin.helix.model.Message;
import com.linkedin.helix.participant.statemachine.StateModel;
import com.linkedin.helix.participant.statemachine.StateModelInfo;
import com.linkedin.helix.participant.statemachine.StateModelParser;
import com.linkedin.helix.participant.statemachine.StateTransitionError;
import com.linkedin.helix.participant.statemachine.Transition;


@StateModelInfo(initialState = "OFFLINE", states = { "LEADER", "STANDBY" })
public class DistClusterControllerStateModel extends StateModel
{
  private static Logger logger = Logger.getLogger(DistClusterControllerStateModel.class);
  private HelixManager _controller = null;
  private final String _zkAddr;

  public DistClusterControllerStateModel(String zkAddr)
  {
    StateModelParser parser = new StateModelParser();
    _currentState = parser.getInitialState(DistClusterControllerStateModel.class);
    _zkAddr = zkAddr;
  }

  @Transition(to="STANDBY",from="OFFLINE")
  public void onBecomeStandbyFromOffline(Message message, NotificationContext context)
  {
    logger.info("Becoming standby from offline");
  }

  @Transition(to="LEADER",from="STANDBY")
  public void onBecomeLeaderFromStandby(Message message, NotificationContext context)
  throws Exception
  {
    String clusterName = message.getPartitionName();
    String controllerName = message.getTgtName();

    logger.info(controllerName + " becomes leader from standby for " + clusterName);
    // System.out.println(controllerName + " becomes leader from standby for " + clusterName);

    if (_controller == null)
    {
      _controller = HelixManagerFactory
          .getZKHelixManager(clusterName, controllerName, InstanceType.CONTROLLER, _zkAddr);
      _controller.connect();
      _controller.startTimerTasks();
    }
    else
    {
      logger.error("controller already exists:" + _controller.getInstanceName()
                   + " for " + clusterName);
    }

  }

  @Transition(to="STANDBY",from="LEADER")
  public void onBecomeStandbyFromLeader(Message message, NotificationContext context)
  {
    String clusterName = message.getPartitionName();
    String controllerName = message.getTgtName();

    logger.info(controllerName + " becoming standby from leader for " + clusterName);

    if (_controller != null)
    {
      _controller.disconnect();
      _controller = null;
    }
    else
    {
      logger.error("No controller exists for " + clusterName);
    }
  }

  @Transition(to="OFFLINE",from="STANDBY")
  public void onBecomeOfflineFromStandby(Message message, NotificationContext context)
  {
    String clusterName = message.getPartitionName();
    String controllerName = message.getTgtName();

    logger.info(controllerName + " becoming offline from standby for cluster:" + clusterName);

  }

  @Transition(to="DROPPED",from="OFFLINE")
  public void onBecomeDroppedFromOffline(Message message, NotificationContext context)
  {
    logger.info("Becoming dropped from offline");
  }

  @Transition(to="OFFLINE",from="DROPPED")
  public void onBecomeOfflineFromDropped(Message message, NotificationContext context)
  {
    logger.info("Becoming offline from dropped");
  }


  @Override
  public void rollbackOnError(Message message, NotificationContext context,
                              StateTransitionError error)
  {
    String clusterName = message.getPartitionName();
    String controllerName = message.getTgtName();

    logger.error(controllerName + " rollbacks on error for " + clusterName);

    if (_controller != null)
    {
      _controller.disconnect();
      _controller = null;
    }

  }

  @Override
  public void reset()
  {
    if (_controller != null)
    {
//      System.out.println("disconnect " + _controller.getInstanceName()
//                         + "(" + _controller.getInstanceType()
//                         + ") from " + _controller.getClusterName());
      _controller.disconnect();
      _controller = null;
    }

  }
}
