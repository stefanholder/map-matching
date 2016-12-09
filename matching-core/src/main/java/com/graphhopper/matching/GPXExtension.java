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

import com.graphhopper.routing.VirtualEdgeIteratorState;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GPXEntry;

/**
 * During map matching this class represents a map matching candidate, i.e. a potential snapped
 * point of a GPX entry. After map matching, this class represents the map matched point of
 * an GPX entry.
 *
 * An instance of this class represents either an undirected real node or a directed virtual node.
 * If this represents a directed virtual node then incoming paths from any previous GPXExtension
 * should arrive through {@link #getIncomingVirtualEdge()} and outgoing paths to any following
 * GPXExtension should start with {@link #getOutgoingVirtualEdge()}. This is achieved by
 * penalizing other edges for routing. Note that virtual nodes are always connected to their
 * adjacent nodes via 2 virtual edges (not counting reverse virtual edges).
 *
 * @author Peter Karich
 * @author kodonnell
 * @author Stefan Holder
 */
public class GPXExtension {
    private final GPXEntry entry;
    private final QueryResult queryResult;
    private final boolean isDirected;
    private final EdgeIteratorState incomingVirtualEdge;
    private final EdgeIteratorState outgoingVirtualEdge;

    /**
     * Creates an undirected candidate for a real node.
     */
    public GPXExtension(GPXEntry entry, QueryResult queryResult) {
        this.entry = entry;
        this.queryResult = queryResult;
        this.isDirected = false;
        this.incomingVirtualEdge = null;
        this.outgoingVirtualEdge = null;
    }

    /**
     * Creates a directed candidate for a virtual node.
     */
    public GPXExtension(GPXEntry entry, QueryResult queryResult,
                        VirtualEdgeIteratorState incomingVirtualEdge,
                        VirtualEdgeIteratorState outgoingVirtualEdge) {
        this.entry = entry;
        this.queryResult = queryResult;
        this.isDirected = true;
        this.incomingVirtualEdge = incomingVirtualEdge;
        this.outgoingVirtualEdge = outgoingVirtualEdge;
    }

    public GPXEntry getEntry() {
        return entry;
    }

    public QueryResult getQueryResult() {
        return queryResult;
    }

    /**
     * Returns true if the snapped point is a virtual node, otherwise returns false.
     */
    public boolean isDirected() {
        return isDirected;
    }

    /**
     * Returns null if this GPXExtension is not directed.
     */
    public EdgeIteratorState getIncomingVirtualEdge() {
        return incomingVirtualEdge;
    }

    /**
     * Returns null if this GPXExtension is not directed.
     */
    public EdgeIteratorState getOutgoingVirtualEdge() {
        return outgoingVirtualEdge;
    }

    @Override
    public String toString() {
        return "GPXExtension{" +
                "closest node=" + queryResult.getClosestNode() +
                " at " + queryResult.getSnappedPoint().getLat() + "," +
                queryResult.getSnappedPoint().getLon() +
                ", incomingEdge=" + incomingVirtualEdge +
                ", outgoingEdge=" + outgoingVirtualEdge +
                '}';
    }
}