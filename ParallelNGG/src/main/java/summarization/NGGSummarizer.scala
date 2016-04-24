import java.io.FileWriter

import org.apache.spark.graphx.Graph
import org.apache.spark.{HashPartitioner, SparkContext}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.distributed.{IndexedRow, IndexedRowMatrix}
import org.apache.spark.rdd.RDD

/**
  * @author Kontopoulos Ioannis
  * If there is an hdfs or nfs compatible file system on the cluster set the third parameter to true
  * when true it temporarily stores the lineage of large graphs
  */
class NGGSummarizer(val sc: SparkContext, val numPartitions: Int, val toCheckpoint: Boolean) extends MultiDocumentSummarizer {

  /**
    * Clusters multiple documents and creates a summary per cluster
    * @param directory directory to extract summaries from
    * @return map containing summaries per cluster
    */
  override def getSummary(directory: String): Map[Int,Array[String]] = {

    val dec = new DocumentEventClustering(sc, numPartitions)
    println("Clustering documents into events...")
    //cluster multiple documents into events
    val eventClusters = dec.getClusters(new java.io.File(directory).listFiles.map(f => f.getAbsolutePath))

    println("Events detected: " + eventClusters.size)

    val ss = new OpenNLPSentenceSplitter("en-sent.bin")

    //variable that holds a summary per event
    var summaries: Map[Int,Array[String]] = Map()

    //for every event extract a summary
    eventClusters.foreach{case (clusterId,docs) =>

      var sentences = Array.empty[StringAtom]

      println("Extracting sentences of the event...")
      //extract the sentences of the event
      docs.foreach{d =>
        val e = new StringEntity
        e.readFile(sc, d, numPartitions)
        val s = ss.getSentences(e).asInstanceOf[RDD[StringAtom]]
        sentences = sentences ++ s.collect
      }

      //give each sentence an id
      val indexedSentences = sc.parallelize(sentences,numPartitions).zipWithIndex
      println("Creating sentence similarity matrix...")
      //get the similarity matrix based on normalized value similarity
      val sMatrix = getSimilarityMatrix(indexedSentences)

      //initialize markov clustering algorithm with 100 iterations,
      //expansion rate of 2, inflation rate of 2 and epsilon value of 0.05
      val mcl = new MatrixMCL(100,2,2.0,0.05)

      println("Markov Clustering on the matrix...")
      //get the sentence clusters
      val markovClusters = mcl.getMarkovClusters(sMatrix).partitionBy(new HashPartitioner(numPartitions))

      //retrieve sentence strings based on sentence ids
      val sentenceClusters = markovClusters.join(indexedSentences.map(s => (s._2, s._1))).map(x => x._2)

      //intersect the graph sentences of a cluster to create subtopics
      var subtopics = Array.empty[Graph[String,Double]]
      val io = new IntersectOperator(0.5)
      val nggc = new NGramGraphCreator(3,3)

      println("Extracting subtopics...")
      sentenceClusters.collect.groupBy(_._1).mapValues(_.map(_._2)).foreach{ case (key,value) =>
        val eFirst = new StringEntity
        eFirst.fromString(sc,value.head.dataStream,numPartitions)
        var intersected = nggc.getGraph(eFirst)
        //intersect current graph to all the next ones
        for (i <- 1 to value.length-1) {
          val curE = new StringEntity
          curE.fromString(sc,value(i).dataStream,numPartitions)
          if (i % 20 == 0) {
            intersected.cache
            if (toCheckpoint) intersected.checkpoint
            intersected.numEdges
          }
          intersected = io.getResult(intersected, nggc.getGraph(curE))
        }
        subtopics :+= intersected
      }

      println("Creating the essence of the event...")
      //merge the subtopic graphs to create the essence of the event
      val mo = new MergeOperator(0.5)
      var eventEssence = subtopics.head
      for (i <- 1 to subtopics.length-1) {
        if (i % 20 == 0) {
          eventEssence.cache
          if (toCheckpoint) eventEssence.checkpoint
          eventEssence.numEdges
        }
        eventEssence = mo.getResult(eventEssence, subtopics(i))
      }
      eventEssence.cache

      println("Comparing each sentence to the essence...")
      //compare each sentence to the merged graph
      var sentencesToFilter = Array.empty[(Double,String)]
      val gsc = new GraphSimilarityCalculator
      indexedSentences.map(_._1.dataStream).collect.foreach{s =>
        val curE = new StringEntity
        curE.fromString(sc,s,numPartitions)
        val gs = gsc.getSimilarity(nggc.getGraph(curE),eventEssence)
        sentencesToFilter :+= (gs.getSimilarityComponents("value"),s)
      }
      eventEssence.unpersist()

      //sort sentences based on their value similarity to the merged graph
      val sortedSentences = sc.parallelize(sentencesToFilter, numPartitions).sortByKey(false, numPartitions).map(_._2).collect

      println("Extracting summary for event...")
      //remove redundant sentences
      summaries += clusterId -> removeRedundantSentences(sortedSentences)
      println("Done!")
    }
    summaries
  }

  /**
    * Creates a similarity matrix between sentences
    * based on the Normalized Value Similarity
    * @param indexedAtoms StringAtoms with an arbitrary matrixId
    * @return similarity matrix
    */
  private def getSimilarityMatrix(indexedAtoms: RDD[(StringAtom, Long)]): IndexedRowMatrix = {
    val idxSentenceArray = indexedAtoms.collect
    //number of sentences
    val numSentences = idxSentenceArray.length

    //add self loops to matrix
    val selfLoops = indexedAtoms.map{case (a,id) => (id.toInt,(id.toInt,1.0))}

    val nggc = new NGramGraphCreator(3,3)
    val gsc = new GraphSimilarityCalculator

    var similarities = Array.empty[(Int,(Int,Double))]

    var next = 1
    //compare all sentences between them and create similarity matrix
    idxSentenceArray.foreach{ case (a,id) =>
      val curE = new StringEntity
      curE.fromString(sc, a.dataStream, numPartitions)
      val curG = nggc.getGraph(curE)
      curG.cache
      for (i <- next to numSentences-1) {
        val e = new StringEntity
        e.fromString(sc, idxSentenceArray(i)._1.dataStream, numPartitions)
        val g = nggc.getGraph(e)
        val gs = gsc.getSimilarity(g, curG)
        similarities ++= Array((id.toInt,(idxSentenceArray(i)._2.toInt,gs.getSimilarityComponents("normalized"))))
      }
      curG.unpersist()
      next += 1
    }
    //convert to indexed row matrix
    val indexedRows = sc.parallelize(similarities, numPartitions).union(selfLoops)
      .groupByKey
      .map(e => IndexedRow(e._1, Vectors.sparse(numSentences, e._2.toSeq)))
    new IndexedRowMatrix(indexedRows)
  }

  /**
    * Removes redundant sentences based on the
    * normalized value similarity between them
    * @param sentences sentences to trim
    * @return the trimmed array of sentences
    */
  private def removeRedundantSentences(sentences: Array[String]): Array[String] = {
    val nggc = new NGramGraphCreator(3,3)
    val gsc = new GraphSimilarityCalculator

    //variable that holds the sentences that should not enter the trimmed array
    var badSentences = Array.empty[String]
    //variable that holds the trimmed sentences
    var trimmedSentences = Array.empty[String]
    val numSentences = sentences.length

    var next = 1
    sentences.foreach{s =>
      if (!badSentences.contains(s)) {
        trimmedSentences :+= s
        val curE = new StringEntity
        curE.fromString(sc,s,numPartitions)
        val curG = nggc.getGraph(curE)
        curG.cache
        for (i <- next to numSentences-1) {
          val curS = sentences(i)
          val e = new StringEntity
          e.fromString(sc,curS,numPartitions)
          val g = nggc.getGraph(e)
          val nvs = gsc.getSimilarity(g,curG).getSimilarityComponents("normalized")
          //if similarity over threshold, it means the sentence contains repeated information
          if (nvs > 0.2) {
            badSentences :+= curS
          }
        }
        curG.unpersist()
      }
      next += 1
    }
    //return trimmed sentences
    trimmedSentences
  }

  /**
    * Save summary to file
    * @param summaries to save
    */
  def saveSummary(summaries: Map[Int, Array[String]]) = {
    try {
      summaries.foreach{case(k,v) =>
        val w = new FileWriter("summary_" + k + ".txt")
        v.foreach(s => w.write(s + "\n"))
        w.close
      }
    }
    catch {
      case ex: Exception => println("Could not write to file. Reason: " + ex.getMessage)
    }
  }

}
