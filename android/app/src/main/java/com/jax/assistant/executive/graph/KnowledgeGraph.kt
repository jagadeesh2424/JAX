package com.jax.assistant.executive.graph

enum class EntityType {
    TOPIC,
    PROJECT,
    PERSON,
    PLACE,
    BOOK,
    COMPANY,
    LEARNING_TOPIC,
    TAG
}

data class GraphNode(
    val id: String,
    val name: String,
    val type: EntityType,
    val attributes: Map<String, String> = emptyMap()
)

data class GraphEdge(
    val sourceId: String,
    val targetId: String,
    val relationship: String,
    val weight: Float = 1.0f
)

class KnowledgeGraph {

    private val nodes = mutableMapOf<String, GraphNode>()
    private val edges = mutableListOf<GraphEdge>()

    init {
        // Seed default core executive entities
        addNode("node_jax", "J.A.X. Platform", EntityType.PROJECT)
        addNode("node_architecture", "Executive Intelligence", EntityType.TOPIC)
        addEdge("node_jax", "node_architecture", "HAS_CAPABILITY")
    }

    fun addNode(id: String, name: String, type: EntityType, attributes: Map<String, String> = emptyMap()): GraphNode {
        val node = GraphNode(id = id, name = name, type = type, attributes = attributes)
        nodes[id] = node
        return node
    }

    fun addEdge(sourceId: String, targetId: String, relationship: String, weight: Float = 1.0f) {
        if (!edges.any { it.sourceId == sourceId && it.targetId == targetId && it.relationship == relationship }) {
            edges.add(GraphEdge(sourceId, targetId, relationship, weight))
        }
    }

    fun findConnectedNodes(nodeId: String): List<GraphNode> {
        val connectedIds = edges.filter { it.sourceId == nodeId || it.targetId == nodeId }
            .flatMap { listOf(it.sourceId, it.targetId) }
            .filter { it != nodeId }
            .toSet()

        return connectedIds.mapNotNull { nodes[it] }
    }

    fun getGraphSummary(): String {
        return "KnowledgeGraph: ${nodes.size} nodes, ${edges.size} semantic relationships indexed."
    }
}
