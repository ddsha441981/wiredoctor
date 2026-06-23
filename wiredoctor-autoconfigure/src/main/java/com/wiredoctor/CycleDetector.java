/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.*;

/**
 * Utility class for detecting circular dependencies within a directed graph.
 * <p>
 * Implements Tarjan's Strongly Connected Components (SCC) algorithm to identify
 * cycles in bean dependency graphs. This provides an accurate, graph-theoretic 
 * approach to cycle detection rather than relying on standard runtime recursion errors.
 *
 * @author Deendayal Kumawat
 * @since 0.1.0
 */
public class CycleDetector {
    
    /**
     * Detects and returns all cyclic dependency chains in the provided graph.
     *
     * @param graph a map representing the directed graph where keys are bean names 
     *              and values are arrays of dependency bean names.
     * @return a list of cycles, where each cycle is represented as a list of bean names.
     */
    public static List<List<String>> detectCycles(Map<String, String[]> graph) {
        List<List<String>> cycles = new ArrayList<>();
        Map<String, Integer> indices = new HashMap<>();
        Map<String, Integer> lowlinks = new HashMap<>();
        Stack<String> stack = new Stack<>();
        Set<String> onStack = new HashSet<>();
        int[] index = {0};

        for (String node : graph.keySet()) {
            if (!indices.containsKey(node)) {
                strongconnect(node, graph, indices, lowlinks, stack, onStack, index, cycles);
            }
        }

        List<List<String>> actualCycles = new ArrayList<>();
        for (List<String> scc : cycles) {
            if (scc.size() > 1) {
                actualCycles.add(scc);
            } else if (scc.size() == 1) {
                String node = scc.get(0);
                String[] edges = graph.getOrDefault(node, new String[0]);
                for (String edge : edges) {
                    if (edge.equals(node)) {
                        actualCycles.add(scc);
                        break;
                    }
                }
            }
        }
        return actualCycles;
    }

    /**
     * Recursive depth-first search step of Tarjan's algorithm to find strongly connected components.
     *
     * @param v        the current vertex being visited
     * @param graph    the full dependency graph
     * @param indices  map of assigned discovery indices for each vertex
     * @param lowlinks map of the smallest index reachable from the vertex
     * @param stack    the current search stack
     * @param onStack  set for O(1) stack containment checks
     * @param index    reference to the global index counter (using array for mutability)
     * @param cycles   the accumulating list of discovered strongly connected components
     */
    private static void strongconnect(String v, Map<String, String[]> graph, 
                                      Map<String, Integer> indices, Map<String, Integer> lowlinks,
                                      Stack<String> stack, Set<String> onStack, int[] index,
                                      List<List<String>> cycles) {
        indices.put(v, index[0]);
        lowlinks.put(v, index[0]);
        index[0]++;
        stack.push(v);
        onStack.add(v);

        String[] edges = graph.getOrDefault(v, new String[0]);
        for (String w : edges) {
            if (!indices.containsKey(w)) {
                strongconnect(w, graph, indices, lowlinks, stack, onStack, index, cycles);
                lowlinks.put(v, Math.min(lowlinks.get(v), lowlinks.get(w)));
            } else if (onStack.contains(w)) {
                lowlinks.put(v, Math.min(lowlinks.get(v), indices.get(w)));
            }
        }

        if (lowlinks.get(v).equals(indices.get(v))) {
            List<String> scc = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                scc.add(w);
            } while (!v.equals(w));
            cycles.add(scc);
        }
    }
}
