package com.wiredoctor;

import java.util.*;

public class CycleDetector {
    
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
