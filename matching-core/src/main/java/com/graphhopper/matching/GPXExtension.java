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
import com.graphhopper.util.GPXEntry;

/**
 * Represents a map matching candidate.
 *
 * @author Peter Karich
 * @author kodonnell
 * @author Stefan Holder
 */
public class GPXExtension {
    public final GPXEntry entry;
    public final QueryResult queryResult;
    public final boolean isDirected;
    public final VirtualEdgeIteratorState incomingVirtualEdge;
    public final VirtualEdgeIteratorState outgoingVirtualEdge;

    public GPXExtension(GPXEntry entry, QueryResult queryResult) {
        this.entry = entry;
        this.queryResult = queryResult;
        this.isDirected = false;
        this.incomingVirtualEdge = null;
        this.outgoingVirtualEdge = null;
    }
    
    public GPXExtension(GPXEntry entry, QueryResult queryResult,
                        VirtualEdgeIteratorState incomingVirtualEdge,
                        VirtualEdgeIteratorState outgoingVirtualEdge) {
        this.entry = entry;
        this.queryResult = queryResult;
        this.isDirected = true;
        this.incomingVirtualEdge = incomingVirtualEdge;
        this.outgoingVirtualEdge = outgoingVirtualEdge;
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