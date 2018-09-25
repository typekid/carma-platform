/*
 * Copyright (C) 2018 LEIDOS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package gov.dot.fhwa.saxton.carma.signal_plugin.ead;

import gov.dot.fhwa.saxton.carma.signal_plugin.appcommon.Constants;
import gov.dot.fhwa.saxton.carma.signal_plugin.appcommon.utils.GlidepathApplicationContext;
import gov.dot.fhwa.saxton.carma.signal_plugin.asd.IntersectionData;
import gov.dot.fhwa.saxton.carma.signal_plugin.appcommon.IGlidepathAppConfig;
import gov.dot.fhwa.saxton.carma.signal_plugin.ead.trajectorytree.*;
import gov.dot.fhwa.saxton.carma.signal_plugin.logger.ILogger;
import gov.dot.fhwa.saxton.carma.signal_plugin.logger.LoggerManager;

import java.util.List;

/**
 * Provides the logic to traverse multiple signalized intersections per white paper from UCR dated 8/18/17.
 */
public class EadAStar implements IEad {

    protected double                    maxAccel_;                  //maximum allowed acceleration, m/s^2
    protected double                    fractionalMaxAccel_;        //maximum acceleration times a reasonable factor
    protected List<Node>                currentPath_;               //path through the current intersection
    protected List<IntersectionData>    intList_;                   //list of all known intersections
    protected int                       currentNodeIndex_ = 1;      //index to the next node downtrack in currentPath_
    protected double                    speedCmd_ = 0.0;            //speed command from the current node, m/s
    protected double                    speedLimit_ = 0.0;          //roadway speed limit, m/s
    protected boolean                   firstCall_ = true;          //is this the first call to getTargetSpeed()?
    protected double				    timeStep_;			        //duration of one time step, sec
    protected long                      prevMethodStartTime_;       //time of the previous call to getTargetSpeed(), ms
    protected double				    stopBoxWidth_;			    //distance from one side of stop box to the other, meters
    protected double                    coarseTimeInc_;             //time increment for coarse planning, sec
    protected double                    coarseSpeedInc_;            //speed increment for coarse planning, m/s
    protected double                    fineTimeInc_;               //time increment for detailed planning, sec
    protected double                    fineSpeedInc_;              //speed increment for detailed planning, m/s                                                        
    protected double                    maxDistanceError_;          //max allowable deviation from plan, m
    protected boolean                   replanNeeded_ = true;       //do we need to replan the trajectory?


    protected String                    desiredFuelCostModel_;
    protected ICostModelFactory         costModelFactory_;
    protected ICostModel                timeCostModel_;             //model of travel time cost between nodes in the tree
    protected ICostModel                fuelCostModel_;             //model of fuel cost to travel between nodes in the tree
    protected INeighborCalculator       coarseNeighborCalc_;        //calculates neighboring nodes for the coarse grid
    protected INeighborCalculator       fineNeighborCalc_;          //calculates neighboring nodes to build the fine grid tree
    protected ITreeSolver               solver_;                    //the tree solver
    protected static ILogger            log_ = LoggerManager.getLogger(EadAStar.class);

    protected static final int          MAX_COURSE_PATH_ATTEMPTS = 100; //max iterations for solving coarse path



    public EadAStar() {

        IGlidepathAppConfig config = GlidepathApplicationContext.getInstance().getAppConfig();
        maxAccel_ = config.getDoubleDefaultValue("defaultAccel", 2.0);
        //params for tree node definitions
        coarseTimeInc_ = config.getDoubleDefaultValue("ead.coarse_time_inc", 5.0);
        coarseSpeedInc_ = config.getDoubleDefaultValue("ead.coarse_speed_inc", 3.0);
        fineTimeInc_ = config.getDoubleDefaultValue("ead.fine_time_inc", 2.0);
        fineSpeedInc_ = config.getDoubleDefaultValue("ead.fine_speed_inc", 1.0);
        desiredFuelCostModel_ = config.getProperty("ead.desiredCostModel");
        //set the max distance error to be half the typical distance between nodes a nominal speed
        speedLimit_ = (double)config.getMaximumSpeed(0.0) / Constants.MPS_TO_MPH;
        fractionalMaxAccel_ = maxAccel_ * 0.75;

        costModelFactory_ = new DefaultCostModelFactory(config);
        fuelCostModel_ = costModelFactory_.getCostModel(desiredFuelCostModel_);
        timeCostModel_ = new TimeCostModel(speedLimit_, maxAccel_);
        coarseNeighborCalc_ = new CoarsePathNeighbors();
        fineNeighborCalc_ = new FinePathNeighbors();
    }

    public List<Node> getCurrentPath() {
        return currentPath_;
    }
    
    @Override
    public void initialize(long timestep, ITreeSolver solver) {
        timeStep_ = (double)timestep / 1000.0;
        solver_ = solver;
    }

    @Override
    public void setStopBoxWidth(double width) {
        stopBoxWidth_ = width;
    }

    ////////////////////
    // protected members
    ////////////////////

    /**
     * Defines a coarse-grained path through all the intersections to get a rough idea of the best long range trajectory.
     * Once this is complete, it will choose a node between the first (current) and second intersections that represents
     * the speed closest to our desired speed, and will return that as the goal node for detailed planning.
     *
     * This method only accounts for travel time, trying to minimize it without violating signal laws.
     * @return a node representing the best goal we can hope to reach after the first intersection
     */
    protected Node planCoarsePath(double operSpeed, Node start) throws Exception {

        //we may be receiving spat signals from a farther intersection but haven't yet seen a MAP message from it,
        // so its rough distance is going to be a bogus, very large value; therefore, only look ahead for as many
        // intersections as we can see with maps defined
        int numInt = intList_.size(); //guaranteed to be >= 1
        int limit = numInt;
        for (int i = 0;  i < limit;  ++i) {
            if (intList_.get(i).map == null) { //this should never be true for i = 0 because first intersection has a map
                numInt = i;
                break;
            }
        }
        if (numInt == 0) {
            String msg = "planCoarsePath can't find any intersections with map data.";
            log_.warn("EAD", msg);
            throw new Exception(msg);
        }

        //define params for a goal node downtrack of the farthest known intersection (where we can recover operating speed)
        //adding an extra distance to make sure the goal passes the fine distance to the second intersection
        //this is the rough location for the second intersection
        //rough distance need to convert from cm to m
        double exitDist = (0.01 * (double)intList_.get(numInt - 1).roughDist) + CoarsePathNeighbors.TYPICAL_INTERSECTION_WIDTH * 2;
        //plan to one more intersection width after the last intersection
        double recoveryDist = CoarsePathNeighbors.TYPICAL_INTERSECTION_WIDTH; 
        exitDist += recoveryDist;

        //At this point we may be dealing with a single intersection that is nearby, or a string of several
        // intersections where the final one may go through a couple cycles before we reach it. So there is no
        // general way of knowing, even roughly, at what point in the final intersection's cycle we will reach it.
        // Short of solving the problem to define a goal node prior to submitting it to the A* solver (redundant),
        // we will simply define the goal node as if all intersections will be green when we arrive (able to stay at
        // operating speed throughout) and let A* try to solve it. There are only a few nodes (one per intersection
        // +1 at the end) so it will be quick.  If it fails, we increment the time of the goal node and try again.

        //this is the fastest case, which is not be able to acheieve
        double exitTime = exitDist / operSpeed;

        //create a neighbor calculator for this tree using a coarse scale grid
        coarseNeighborCalc_.initialize(intList_, numInt, coarseTimeInc_, coarseSpeedInc_);
        coarseNeighborCalc_.setOperatingSpeed(operSpeed);

        //while no solution and we have not exceeded our cycle limit
        List<Node> path = null;
        Node coarseGoal = null;
        int iter = 0;
        while ((path == null  ||  path.size() == 0)  &&  iter < MAX_COURSE_PATH_ATTEMPTS) {

            coarseGoal = new Node(exitDist, exitTime, operSpeed);
            log_.debug("EAD", "planCoarsePath solving iter " + iter + " with " + numInt +
                    " useful intersections and a course goal of " + coarseGoal.toString());

            //find the best path through this tree
            timeCostModel_.setGoal(coarseGoal);
            timeCostModel_.setTolerances(new Node(1.0, 0.51 * coarseTimeInc_, 0.51 * coarseSpeedInc_));
            path = solver_.solve(start, timeCostModel_, coarseNeighborCalc_);

            //increment goal time
            exitTime += 5*coarseTimeInc_;
            ++iter;
        }

        //if we still haven't found a solution then throw exception
        //the size of path list should be at least 3
        //because element 0 is our start node, element 1 is our node at the first intersection and
        // element 2 is the node after the first intersection
        if (path == null  ||  path.size() < 3) {
            String msg = "planCoarsePath solver was unable to define a path after " + iter + " iterations.";
            log_.error("EAD", msg);
            throw new Exception(msg);
        }
        log_.debug("EAD", "planCoarsePath found a solution after " + iter + " iterations.");

        //we always use the node after current intersection as the goal node
        Node fineGoal = path.get(2);

        //summarize the chosen path
        summarizeCoarsePath(path, coarseGoal, fineGoal);
        
        return fineGoal;
    }


    /**
     * Finds the best fine-grained path from current location to the far side of the nearest intersection
     * that minimizes fuel cost to reach the travel time goal.
     * @param goal - the node beyond the nearest intersection that we are trying to reach
     * @return - the best path to the goal node
     * @apiNote neighbor nodes are located according to the increments in each dimension specified by the
     * config file parameters fineTimeInc, fineDistInc and fineSpeedInc.
     */
    protected List<Node> planDetailedPath(Node start, Node goal) throws Exception {
        log_.debug("EAD", "Entering planDetailedPath with start = " + start.toString() + ", goal = " + goal.toString());
        
        //create a neighbor calculator for this tree using a detailed grid
        fineNeighborCalc_.initialize(intList_, 1, fineTimeInc_, fineSpeedInc_);

        //find the best path through this tree [use AStarSolver]
        fuelCostModel_.setGoal(goal);
        fuelCostModel_.setTolerances(new Node(0.51*fineSpeedInc_*fineTimeInc_, 0.51*fineTimeInc_, 0.51*fineSpeedInc_));
        List<Node> path = solver_.solve(start, fuelCostModel_, fineNeighborCalc_);
        if (path == null  ||  path.size() == 0) {
            String msg = "///// planDetailedPath solver was unable to define a path.";
            log_.error("EAD", msg);
            throw new Exception(msg);
        }
        
        //evaluate the chosen path [summarizeDetailedPath]
        summarizeDetailedPath(path, goal);

        return path;
    }


    /**
     * Logs pertinent info about the coarse solution for human consumption.
     */
    protected void summarizeCoarsePath(List<Node> path, Node coarseGoal, Node fineGoal) {

        log_.debug("EAD", "Coarse plan covered " + intList_.size() + " intersections at distances of:");
        log_.debugf("EAD", "    %.0f m", intList_.get(0).dtsb);
        for (int i = 1;  i < intList_.size();  ++i) {
            log_.debugf("EAD", "    %.0f m", 0.01 * (double)intList_.get(i).roughDist);
        }
        log_.info("EAD", "Coarse path plan:");
        for (Node n : path) {
            log_.info("EAD", "    " + n.toString());
        }
        log_.debug("EAD", "Coarse path attempted to reach goal: " + coarseGoal.toString());
        log_.debug("EAD", "Coarse path yielded goal for detailed planning of: " + fineGoal.toString());
    }


    /**
     * Logs pertinent info about the detailed solution through the current intersection for human consumption.
     */
    protected void summarizeDetailedPath(List<Node> path, Node goal) {

        log_.info("EAD", "///// Detailed path plan:");
        for (Node n : path) {
            log_.info("EAD", "    " + n.toString());
        }
        log_.debug("EAD", "Detailed path attempted to reach goal: " + goal.toString());
    }

    /**
     * Intended for use in testing only!  Sets up the objects memory of previous call.
     * @param cmd - the speed command from the previous iteration
     */
    protected void injectSpeedCmd(double cmd) {
        speedCmd_ = cmd;
        firstCall_ = false;
    }

    /**
     * Generates a plan based on the input data
     * 
     * @param speed The current speed of the vehicle
     * @param operSpeed The target operating speed of the vehicle
     * @param intersections A sorted list of intersection data with the nearest intersections appearing earlier in the list
     * 
     * @return A list of node defining the planned vehicle trajectory
     */
    public List<Node> plan(double speed, double operSpeed, 
        List<IntersectionData> intersections) throws Exception {

        if (intersections == null  ||  intersections.size() == 0) {
            String msg = "getTargetSpeed invoked with a empty intersection list.";
            log_.error("EAD", msg);
            throw new Exception(msg);
        }

        intList_ = intersections;
        long methodStartTime = System.currentTimeMillis();

        ////////// BUILD & SOLVE THE DIJKSTRA TREE TO DEFINE THE BEST PATH

        Node goal;

        //define starting node and reset DDT since we are beginning execution of a new path
        speedCmd_ = speed; // The starting speed command is the current speed
        Node startNode = new Node(0.0, 0.0, speedCmd_);
        currentNodeIndex_ = 0;
        currentPath_ = null;

        //perform coarse planning to determine the goal node downtrack of the first intersection [planCoarsePath]
        goal = planCoarsePath(operSpeed, startNode);

        //build a detailed plan to reach the near-term goal node downtrack of first intersection [planDetailedPath]
        try {
            currentPath_ = planDetailedPath(startNode, goal);
        }catch (Exception e) {
            log_.warn("EAD", "getTargetSpeed trapped exception from planDetailedPath: " + e.toString());
        }

        if (currentPath_ == null  ||  currentPath_.size() == 0) {
            String msg = "getTargetSpeed produced an unusable detailed path.";
            log_.error("EAD", msg);
            throw new Exception(msg);
        }

        prevMethodStartTime_ = methodStartTime;
        long totalTime = System.currentTimeMillis() - methodStartTime;
        log_.info("EAD", "getTargetSpeed completed in " + totalTime + " ms.");
        
        return currentPath_;
    }
}
