
package stainless.utils

import scala.collection.mutable.{ ListBuffer, Map => MutableMap, Set => MutableSet, Queue => MutableQueue }

/**
 * Describes a Graph of Computation that is incrementally refined/updated. [[Node]]s can be inserted
 * (and updated), sequentially to build a full graph. After each [[update]], [[compute]] is called at
 * most once with the set of all nodes not yet computed -- which (indirect, possibly cyclic) dependencies
 * are all known -- with the dependencies themselves, hence a node might be passed to [[compute]]
 * several times. When any dependency of a node is updated, the node is recomputed.
 */
trait IncrementalComputationalGraph[Id, Input, Result] {

  /******************* Public Interface ***********************************************************/

  /**
   * Insert (or override) a given node into the graph, then perform computation
   * based on the graph state. Return the computed values.
   */
  def update(id: Id, in: Input, deps: Set[Id]): Option[Result] = {
    update(Node(id, in, deps))
  }

  /******************* Customisation Points *******************************************************/

  /**
   * Produce some result for the set of nodes that are all ready.
   *
   * If is guarantee that [[ready]] contains all the dependencies for all element of [[ready]].
   *
   * The result itself is not used by [[IncrementalComputationalGraph]].
   */
  protected def compute(ready: Set[(Id, Input)]): Result

  /**
   * Determine whether the new value for a node is equivalent to the old value, given that
   * they have the same id (enforced by the graph model) and the same set of dependencies.
   *
   * The default implementation make the conservative assumption that two inputs are equivalent
   * if and only if they are equal. This function can be redefined in subclasses if needed.
   */
  protected def equivalent(id: Id, deps: Set[Id], oldInput: Input, newInput: Input): Boolean = {
    oldInput == newInput
  }

  /******************* Implementation *************************************************************/

  /**
   * Representation of a [[Node]]:
   *  - It's [[id]] fully identifies a node (i.e. two nodes are equal <=> their ids are equal).
   *    This allows overriding a node simply by inserting a new node with the same identifier.
   *  - [[in]] denotes the input value for the node which is used for the computation.
   *  - [[deps]] holds the set of **direct** dependencies.
   * Indirect dependencies is computed by [[IncrementalComputationalGraph]] itself.
   */
  private case class Node(id: Id, in: Input, deps: Set[Id]) {
    override def equals(any: Any): Boolean = any match {
      case Node(other, _, _) => id == other
      case _ => false
    }

    override def hashCode = id.hashCode
  }

  /**
   * Implementation for the public [[update]] function.
   *
   * If the new node is equivalent to an old one, do nothing. Otherwise, process normally.
   */
  private def update(n: Node): Option[Result] = {
    def run(delete: Boolean) = {
      if (delete) remove(n)
      insert(n)
      process()
    }

    nodes get n.id match {
      case Some(m) if (m.deps == n.deps) && equivalent(n.id, n.deps, m.in, n.in) => None // nothing new
      case mOpt => run(mOpt.isDefined)
    }
  }

  /**
   * Some nodes might not yet be fully known, yet we have some evidence (through other nodes'
   * dependencies) that they exists. We therefore keep track of dependencies using their
   * identifiers, and we keep track of the mapping between identifiers and nodes in [[nodes]].
   */
  private val nodes = MutableMap[Id, Node]()

  /** Set of nodes that have not yet been inserted into the graph, but are known to exist. */
  val missings = MutableSet[Id]() // <- actually unused

  /* The set of nodes not yet computed, but seen. */
  private val toCompute = MutableSet[Node]()

  /*
   * A reverse graph of dependencies. Because we might not fully know the node yet.
   * we use the known identifiers for the mapping.
   */
  private val reverse = MutableMap[Id, MutableSet[Node]]()


  /** Insert a new node & update the graph. */
  private def insert(n: Node): Unit = {
    nodes += n.id -> n
    // missings -= n.id
    toCompute += n
    n.deps foreach { depId =>
      if (!(nodes contains depId)) missings += depId
      reverse.getOrElseUpdate(depId, MutableSet()) += n
    }
  }

  /** Remove an existing node from the graph. */
  private def remove(n: Node): Unit = {
    nodes -= n.id
    if (reverse contains n.id) missings += n.id
    reverse.values foreach { _ -= n }

    mark(n)
  }

  /** Recursively put the nodes that depends on [[n]] into [[toCompute]]. */
  private def mark(n: Node): Unit = {
    val seen = MutableSet[Node]()
    val queue = MutableQueue[Node]()

    // Add nodes that depend on n to the queue, if not yet visited
    def add(n: Node): Unit = {
      reverse get n.id foreach { sǝᴉɔuǝpuǝdǝp =>
        queue ++= sǝᴉɔuǝpuǝdǝp filterNot seen
      }
    }

    // Visit a node and queue the ones that depend on it.
    def visit(n: Node): Unit = if (seen contains n) { /* visited via another path */ } else {
      seen += n
      toCompute += n

      add(n)
    }

    // Start visiting the node itself, then loop until all nodes that
    // depend on it are visited.
    visit(n)
    while (queue.nonEmpty) {
      val head = queue.dequeue
      visit(head)
    }
  }

  /** Determine the set of nodes that can be computed, and compute them. */
  private def process(): Option[Result] = {
    val ready: Set[Node] = {
      val setOfSet = for {
        n <- toCompute
        allDeps <- dependencies(n)
      } yield allDeps

      if (setOfSet.isEmpty) Set()
      else setOfSet reduce { _ union _ }
    }

    toCompute --= ready
    if (ready.isEmpty) None
    else {
      val args = ready map { n => (n.id, n.in) }
      Some(compute(args))
    }
  }

  /**
   * Compute the set of (indirect or not) dependencies,
   * or return None if any dependency is missing from the graph.
   */
  // TODO if the graph is big, we might want to introduce some caching at this level.
  //      It should get invalidated on insert/remove however.
  private def dependencies(n: Node): Option[Set[Node]] = {
    val seen = MutableSet[Node]()
    val deps = MutableSet[Node]()
    val queue = MutableQueue[Node]()
    var complete = true

    // Called when we have a missing dependency.
    def abort(): Unit = {
      complete = false
    }

    // Visit this node, and queue its dependencies when we have all of them, abort otherwise.
    def visit(n: Node): Unit = if (seen contains n) { /* visited via another path */ } else {
      seen += n
      deps += n

      if (n.deps subsetOf nodes.keySet) {
        val nexts = n.deps map nodes filterNot seen
        queue ++= nexts
      } else abort()
    }

    visit(n)
    while (complete && queue.nonEmpty) {
      val head = queue.dequeue
      visit(head)
    }

    if (complete) Some(deps.toSet)
    else None
  }

}
