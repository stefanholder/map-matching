/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper GmbH licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.matching;

import com.graphhopper.routing.weighting.FastestWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.bmw.hmm.SequenceState;
import com.bmw.hmm.ViterbiAlgorithm;
import com.graphhopper.routing.DijkstraBidirectionRef;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.QueryGraph;
import com.graphhopper.routing.util.*;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class matches real world GPX entries to the digital road network stored
 * in GraphHopper. The algorithm is a simple 4 phase process:
 * <p>
 * <ol>
 * <li>Lookup Phase: Find some closest edges for every GPX entry</li>
 * <li>Custom Weighting Phase: Create a weighting object where those edges will
 * be preferred</li>
 * <li>Search Phase: Calculate the path and its list of edges from the best
 * start to the best end edge</li>
 * <li>Match Phase: Associate all GPX entries for every edge</li>
 * </ol>
 * <p>
 *
 * Note: currently tested with very close GPX points only. Will fail if best
 * match for start or end node is incorrect. Performance improvements possible
 * if not the full but only partial routes are calculated this will also improve
 * accuracy as currently all loops in a GPX trail are automatically removed.
 * <p>
 * See http://en.wikipedia.org/wiki/Map_matching
 *
 * @author Peter Karich
 * @author Michael Zilske
 * @author Stefan Holder
 */
public class MapMatching {

    private final Graph graph;
    private final LocationIndexMatch locationIndex;
    private final FlagEncoder encoder;
    private final TraversalMode traversalMode;

    private double measurementErrorSigma = 50.0;

    private double transitionProbabilityBeta = 0.00959442;
    private int maxVisitedNodes = 1000;
    private final int nodeCount;
    private DistanceCalc distanceCalc = new DistancePlaneProjection();
    private Weighting weighting;

    public MapMatching(Graph graph, LocationIndexMatch locationIndex, FlagEncoder encoder) {
        this.graph = graph;
        this.nodeCount = graph.getNodes();
        this.locationIndex = locationIndex;

        // TODO initialization of start values for the algorithm is currently done explicitely via node IDs! 
        // To fix this use instead: traversalMode.createTraversalId(iter, false);
//        this.traversalMode = graph.getExtension() instanceof TurnCostExtension
//                ? TraversalMode.EDGE_BASED_2DIR : TraversalMode.NODE_BASED;                
        this.traversalMode = TraversalMode.NODE_BASED;
        this.encoder = encoder;
        this.weighting = new FastestWeighting(encoder);
    }

    /**
     * This method overwrites the default fastest weighting.
     */
    public void setWeighting(Weighting weighting) {
        this.weighting = weighting;
    }

    public void setDistanceCalc(DistanceCalc distanceCalc) {
        this.distanceCalc = distanceCalc;
    }

    /**
     * Beta parameter of the exponential distribution for modeling transition
     * probabilities. Empirically computed from the Microsoft ground truth data
     * for shortest route lengths and 60 s sampling interval but also works for
     * other sampling intervals.
     */
    public void setTransitionProbabilityBeta(double transitionProbabilityBeta) {
        this.transitionProbabilityBeta = transitionProbabilityBeta;
    }

    /**
     * Standard deviation of the normal distribution [m] used for modeling the
     * GPS error taken from Newson and Krumm.
     */
    public void setMeasurementErrorSigma(double measurementErrorSigma) {
        this.measurementErrorSigma = measurementErrorSigma;
    }

    public void setMaxVisitedNodes(int maxNodesToVisit) {
        this.maxVisitedNodes = maxNodesToVisit;
    }

    /**
     * This method does the actual map matching.
     * <p>
     * @param gpxList the input list with GPX points which should match to edges
     * of the graph specified in the constructor
     */
    public MatchResult doWork(List<GPXEntry> gpxList) {
        if (gpxList.size() < 2) {
            throw new IllegalArgumentException("Too few coordinates in input file (" + gpxList.size() + "). Correct format?");
        }

        final EdgeFilter edgeFilter = new DefaultEdgeFilter(encoder);
        
        // Compute all candidates first. Would be great if this could be done on-the-fly instead.
        final List<QueryResult> allCandidates = new ArrayList<QueryResult>();
        List<TimeStep<GPXExtension, GPXEntry, Path>> timeSteps = createTimeSteps(gpxList, edgeFilter, allCandidates);
        // printMinDistances(timeSteps);
        
        if (allCandidates.size() < 2) {
            throw new IllegalArgumentException("Too few matching coordinates (" + allCandidates.size() + "). Wrong region imported?");
        }
        if (timeSteps.size() < 2) {
            throw new IllegalStateException("Coordinates produced too few time steps " + timeSteps.size() + ", gpxList:" + gpxList.size());
        }
        
        final QueryGraph queryGraph = new QueryGraph(graph).setUseEdgeExplorerCache(true);
        queryGraph.lookup(allCandidates);
        
        List<SequenceState<GPXExtension, GPXEntry, Path>> seq = computeViterbiSequence(timeSteps, gpxList, queryGraph);
        
        final EdgeExplorer explorer = queryGraph.createEdgeExplorer(edgeFilter);        
        MatchResult matchResult = computeMatchResult(seq, gpxList, allCandidates, explorer);
        
        return matchResult;
    }

    /**
     * Creates TimeSteps for the GPX entries but does not create emission or transition probabilities.
     * 
     * @param outAllCandidates ouptut parameter for all candidates, must be an empty list.
     */
    private List<TimeStep<GPXExtension, GPXEntry, Path>> createTimeSteps(List<GPXEntry> gpxList, EdgeFilter edgeFilter,
    		List<QueryResult> outAllCandidates) {
        int indexGPX = 0;
        TimeStep<GPXExtension, GPXEntry, Path> prevTimeStep = null;        
        final List<TimeStep<GPXExtension, GPXEntry, Path>> timeSteps = new ArrayList<>();
        
        for (GPXEntry entry : gpxList) {
            if (prevTimeStep == null
                    || distanceCalc.calcDist(prevTimeStep.observation.getLat(), prevTimeStep.observation.getLon(),
                    		entry.getLat(), entry.getLon()) > 2 * measurementErrorSigma
                    // always include last point
                    || indexGPX == gpxList.size() - 1) {
                final List<QueryResult> candidates = locationIndex.findNClosest(entry.lat, entry.lon, edgeFilter, measurementErrorSigma);
                outAllCandidates.addAll(candidates);
                final List<GPXExtension> gpxExtensions = new ArrayList<GPXExtension>();
                for (QueryResult candidate : candidates) {
                    gpxExtensions.add(new GPXExtension(entry, candidate, indexGPX));
                }
                final TimeStep<GPXExtension, GPXEntry, Path> timeStep = new TimeStep<>(entry, gpxExtensions);
                timeSteps.add(timeStep);
                prevTimeStep = timeStep;
            }
            indexGPX++;
        }  
        return timeSteps;
    }

	private List<SequenceState<GPXExtension, GPXEntry, Path>> computeViterbiSequence(
			List<TimeStep<GPXExtension, GPXEntry, Path>> timeSteps, List<GPXEntry> gpxList,
			final QueryGraph queryGraph) {
		final HmmProbabilities probabilities = new HmmProbabilities(measurementErrorSigma, transitionProbabilityBeta);
        final ViterbiAlgorithm<GPXExtension, GPXEntry, Path> viterbi = new ViterbiAlgorithm<>();
        
        int timeStepCounter = 0;
        TimeStep<GPXExtension, GPXEntry, Path> prevTimeStep = null;
        for (TimeStep<GPXExtension, GPXEntry, Path> timeStep : timeSteps) {
            	computeEmissionProbabilities(timeStep, probabilities);

                if (prevTimeStep == null) {
                	viterbi.startWithInitialObservation(timeStep.observation, timeStep.candidates, timeStep.emissionLogProbabilities);
                } else {
                	computeTransitionProbabilities(prevTimeStep, timeStep, probabilities, queryGraph);
                	viterbi.nextStep(timeStep.observation, timeStep.candidates, timeStep.emissionLogProbabilities,
                			timeStep.transitionLogProbabilities, timeStep.roadPaths);
                }
                if (viterbi.isBroken()) {
                    String likelyReasonStr = "";
                    if (prevTimeStep != null) {
                    	 GPXEntry prevGPXE = prevTimeStep.observation;
                         GPXEntry gpxe = timeStep.observation;
                         double dist = distanceCalc.calcDist(prevGPXE.lat, prevGPXE.lon, gpxe.lat, gpxe.lon);
                         if (dist > 2000) {
                             likelyReasonStr = "Too long distance to previous measurement? " + Math.round(dist) + "m, ";
                         }
                    }

                    throw new RuntimeException("Sequence is broken for submitted track at time step " + timeStepCounter 
                    		+ " (" + gpxList.size() + " points). "
                            + likelyReasonStr
                            + "observation:" + timeStep.observation + ", candidates: " + getSnappedCandidates(timeStep.candidates));
                }
                
                timeStepCounter++;
                prevTimeStep = timeStep;
        }
    
        List<SequenceState<GPXExtension, GPXEntry, Path>> seq = viterbi.computeMostLikelySequence();
		return seq;
	}    
    
	private void computeEmissionProbabilities(TimeStep<GPXExtension, GPXEntry, Path> timeStep, HmmProbabilities probabilities) {
        for (GPXExtension candidate : timeStep.candidates) {
        	// road distance difference in meters
            final double distance = candidate.getQueryResult().getQueryDistance();
            timeStep.addEmissionLogProbability(candidate, probabilities.emissionLogProbability(distance));
        }
    }

    private void computeTransitionProbabilities(TimeStep<GPXExtension, GPXEntry, Path> prevTimeStep,
			TimeStep<GPXExtension, GPXEntry, Path> timeStep, HmmProbabilities probabilities, QueryGraph queryGraph) {
        final double linearDistance = distanceCalc.calcDist(prevTimeStep.observation.lat,
       		 prevTimeStep.observation.lon, timeStep.observation.lat, timeStep.observation.lon);
        
        // time difference in seconds
        final double timeDiff = (timeStep.observation.getTime() - prevTimeStep.observation.getTime()) / 1000.0;
    	
    	 for (GPXExtension from : prevTimeStep.candidates) {
             for (GPXExtension to : timeStep.candidates) {
                 // TODO allow CH, then optionally use cached one-to-many Dijkstra to improve speed
                 final DijkstraBidirectionRef algo = new DijkstraBidirectionRef(queryGraph, encoder, weighting, traversalMode);
                 algo.setMaxVisitedNodes(maxVisitedNodes);
                 final Path path = algo.calcPath(from.getQueryResult().getClosestNode(), to.getQueryResult().getClosestNode());
                 if (path.isFound()) {
                     timeStep.addRoadPath(from, to, path);
                     final double transitionLogProbability = probabilities.transitionLogProbability(
                    		 path.getDistance(), linearDistance, timeDiff);
                     timeStep.addTransitionLogProbability(from, to, transitionLogProbability);
                 }
             }
         }
    }
    
	private MatchResult computeMatchResult(List<SequenceState<GPXExtension, GPXEntry, Path>> seq,
			List<GPXEntry> gpxList, List<QueryResult> allCandidates, EdgeExplorer explorer) {
		// every virtual edge maps to its real edge where the orientation is already correct!
        // TODO use traversal key instead of string!
        final Map<String, EdgeIteratorState> virtualEdgesMap = new HashMap<String, EdgeIteratorState>();

        for (QueryResult candidate : allCandidates) {
            fillVirtualEdges(virtualEdgesMap, explorer, candidate);
        }

        List<EdgeMatch> edgeMatches = new ArrayList<EdgeMatch>();        
        double distance = 0.0;
        long time = 0;
        EdgeIteratorState currentEdge = null;
        List<GPXExtension> gpxExtensions = new ArrayList<GPXExtension>();
        GPXExtension queryResult = seq.get(0).state;
        gpxExtensions.add(queryResult);
        for (int j = 1; j < seq.size(); j++) {
            GPXExtension nextQueryResult = seq.get(j).state;
            Path path = seq.get(j).transitionDescriptor;
            distance += path.getDistance();
            time += path.getTime();
            for (EdgeIteratorState edgeIteratorState : path.calcEdges()) {
                EdgeIteratorState directedRealEdge = resolveToRealEdge(virtualEdgesMap, edgeIteratorState);
                if (directedRealEdge == null) {
                    throw new RuntimeException("Did not find real edge for " + edgeIteratorState.getEdge());
                }
                if (currentEdge == null || !equalEdges(directedRealEdge, currentEdge)) {
                    if (currentEdge != null) {
                        EdgeMatch edgeMatch = new EdgeMatch(currentEdge, gpxExtensions);
                        edgeMatches.add(edgeMatch);
                        gpxExtensions = new ArrayList<GPXExtension>();
                    }
                    currentEdge = directedRealEdge;
                }
            }
            gpxExtensions.add(nextQueryResult);
            queryResult = nextQueryResult;
        }
        if (edgeMatches.isEmpty()) {
            throw new IllegalStateException("No edge matches found for path. Too short? Sequence size " + seq.size());
        }
        EdgeMatch lastEdgeMatch = edgeMatches.get(edgeMatches.size() - 1);
        if (!gpxExtensions.isEmpty() && !equalEdges(currentEdge, lastEdgeMatch.getEdgeState())) {
            edgeMatches.add(new EdgeMatch(currentEdge, gpxExtensions));
        } else {
            lastEdgeMatch.getGpxExtensions().addAll(gpxExtensions);
        }
        MatchResult matchResult = new MatchResult(edgeMatches);
        matchResult.setMatchMillis(time);
        matchResult.setMatchLength(distance);

        //////// Calculate stats to determine quality of matching //////// 
        double gpxLength = 0;
        GPXEntry prevEntry = gpxList.get(0);
        for (int i = 1; i < gpxList.size(); i++) {
            GPXEntry entry = gpxList.get(i);
            gpxLength += distanceCalc.calcDist(prevEntry.lat, prevEntry.lon, entry.lat, entry.lon);
            prevEntry = entry;
        }

        long gpxMillis = gpxList.get(gpxList.size() - 1).getTime() - gpxList.get(0).getTime();
        matchResult.setGPXEntriesMillis(gpxMillis);
        matchResult.setGPXEntriesLength(gpxLength);
		return matchResult;
	}    
    
	private boolean equalEdges(EdgeIteratorState edge1, EdgeIteratorState edge2) {
        return edge1.getEdge() == edge2.getEdge()
                && edge1.getBaseNode() == edge2.getBaseNode()
                && edge1.getAdjNode() == edge2.getAdjNode();
    }

    private EdgeIteratorState resolveToRealEdge(Map<String, EdgeIteratorState> virtualEdgesMap, EdgeIteratorState edgeIteratorState) {
        if (isVirtualNode(edgeIteratorState.getBaseNode()) || isVirtualNode(edgeIteratorState.getAdjNode())) {
            return virtualEdgesMap.get(virtualEdgesMapKey(edgeIteratorState));
        } else {
            return edgeIteratorState;
        }
    }

    private boolean isVirtualNode(int node) {
        return node >= nodeCount;
    }

    /**
     * Fills the minFactorMap with weights for the virtual edges.
     */
    private void fillVirtualEdges(Map<String, EdgeIteratorState> virtualEdgesMap,
            EdgeExplorer explorer, QueryResult qr) {
        if (isVirtualNode(qr.getClosestNode())) {
            EdgeIterator iter = explorer.setBaseNode(qr.getClosestNode());
            while (iter.next()) {
                int node = traverseToClosestRealAdj(explorer, iter);
                if (node == qr.getClosestEdge().getAdjNode()) {
                    virtualEdgesMap.put(virtualEdgesMapKey(iter), qr.getClosestEdge().detach(false));
                    virtualEdgesMap.put(reverseVirtualEdgesMapKey(iter), qr.getClosestEdge().detach(true));
                } else if (node == qr.getClosestEdge().getBaseNode()) {
                    virtualEdgesMap.put(virtualEdgesMapKey(iter), qr.getClosestEdge().detach(true));
                    virtualEdgesMap.put(reverseVirtualEdgesMapKey(iter), qr.getClosestEdge().detach(false));
                } else {
                    throw new RuntimeException();
                }
            }
        }
    }

    private String virtualEdgesMapKey(EdgeIteratorState iter) {
        return iter.getBaseNode() + "-" + iter.getEdge() + "-" + iter.getAdjNode();
    }

    private String reverseVirtualEdgesMapKey(EdgeIteratorState iter) {
        return iter.getAdjNode() + "-" + iter.getEdge() + "-" + iter.getBaseNode();
    }

    private int traverseToClosestRealAdj(EdgeExplorer explorer, EdgeIteratorState edge) {
        if (!isVirtualNode(edge.getAdjNode())) {
            return edge.getAdjNode();
        }

        EdgeIterator iter = explorer.setBaseNode(edge.getAdjNode());
        while (iter.next()) {
            if (iter.getAdjNode() != edge.getBaseNode()) {
                return traverseToClosestRealAdj(explorer, iter);
            }
        }
        throw new IllegalStateException("Cannot find adjacent edge " + edge);
    }

    private String getSnappedCandidates(Collection<GPXExtension> candidates) {
        String str = "";
        for (GPXExtension gpxe : candidates) {
            if (!str.isEmpty()) {
                str += ", ";
            }
            str += "distance: " + gpxe.queryResult.getQueryDistance() + " to " + gpxe.queryResult.getSnappedPoint();
        }
        return "[" + str + "]";
    }

    private void printMinDistances(List<TimeStep<GPXExtension, GPXEntry, Path>> timeSteps) {
        TimeStep<GPXExtension, GPXEntry, Path> prevStep = null;
        int index = 0;
        for (TimeStep<GPXExtension, GPXEntry, Path> ts : timeSteps) {
            if (prevStep != null) {
                double dist = distanceCalc.calcDist(prevStep.observation.lat, prevStep.observation.lon, ts.observation.lat, ts.observation.lon);
                double minCand = Double.POSITIVE_INFINITY;
                for (GPXExtension prevGPXE : prevStep.candidates) {
                    for (GPXExtension gpxe : ts.candidates) {
                        GHPoint psp = prevGPXE.queryResult.getSnappedPoint();
                        GHPoint sp = gpxe.queryResult.getSnappedPoint();
                        double tmpDist = distanceCalc.calcDist(psp.lat, psp.lon, sp.lat, sp.lon);
                        if (tmpDist < minCand) {
                            minCand = tmpDist;
                        }
                    }
                }
                System.out.println(index + ": " + Math.round(dist) + "m, minimum candidate: " + Math.round(minCand) + "m");
                index++;
            }

            prevStep = ts;
        }
    }

    private static class MyPath extends Path {

        public MyPath(Graph graph, FlagEncoder encoder) {
            super(graph, encoder);
        }

        @Override
        public Path setFromNode(int from) {
            return super.setFromNode(from);
        }

        @Override
        public void processEdge(int edgeId, int adjNode) {
            super.processEdge(edgeId, adjNode);
        }
    };

    public Path calcPath(MatchResult mr) {
        MyPath p = new MyPath(graph, encoder);
        if (!mr.getEdgeMatches().isEmpty()) {
            p.setFromNode(mr.getEdgeMatches().get(0).getEdgeState().getBaseNode());
            for (EdgeMatch em : mr.getEdgeMatches()) {
                p.processEdge(em.getEdgeState().getEdge(), em.getEdgeState().getAdjNode());
            }

            // TODO p.setWeight(weight);
            p.setFound(true);

            return p;
        } else {
            return p;
        }
    }
}
