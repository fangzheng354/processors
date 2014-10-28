package edu.arizona.sista.matcher

import scala.util.control.Breaks._
import scala.collection.mutable.HashMap
import edu.arizona.sista.processors.{Document, Sentence}
import edu.arizona.sista.matcher.dependencies.DependencyExtractor
import NamedEntityExtractor.getEntityMentions

class ExtractorEngine(val spec: String, val actions: AnyRef) {
  // invokes actions through reflection
  val mirror = new ActionMirror(actions)

  // extractors defined in the spec, separated by at least one blank line
  val extractors = spec split """(?m)^\s*$""" map (_.trim) filter (_ != "") map mkExtractor

  // the minimum number of iterations required for every rule to run at least once
  val minIterations = extractors.map(_.startsAt).max

  def extractFrom(document: Document) = {
    val state = new State(document)
    state.update(getEntityMentions(document))

    var updated = true

    breakable {
      for (iter <- Stream.from(1)) {
        var updated = false

        for (extractor <- extractors if extractor.priority matches iter) {
          val mentions = extractor.extractFrom(document, state)
          if (mentions.nonEmpty) {
            state.update(mentions)
            updated = true
          }
        }

        if (!updated && iter >= minIterations) break
      }
    }

    state.allMentions
  }

  def mkExtractor(spec: String): NamedExtractor = {
    val fieldPat = ExtractorEngine.fieldPattern
    val it = for (fieldPat(name, value) <- fieldPat findAllIn spec) yield (name -> value)
    val fields = Map(it.toSeq: _*)
    val name = fields("name")
    val priority = Priority(fields.getOrElse("priority", ExtractorEngine.defaultPriority))
    val action = mirror.reflect(fields("action"))
    val extractorType = fields.getOrElse("type", ExtractorEngine.defaultExtractorType)
    val pattern = fields("pattern").drop(2).dropRight(2)
    val extractor = ExtractorEngine.registeredExtractors(extractorType)(pattern)
    new NamedExtractor(name, priority, extractor, action)
  }
}

object ExtractorEngine {
  type ExtractorBuilder = String => Extractor

  // registered extractors go here
  private val registeredExtractors = new HashMap[String, ExtractorBuilder]

  val defaultExtractorType = "arizona"
  val defaultPriority = "1+"

  // our extractor is registered by default
  register(defaultExtractorType, DependencyExtractor.apply)

  // register extractors to be used in our rules
  def register(extractorType: String, extractorBuilder: ExtractorBuilder) {
    registeredExtractors += (extractorType -> extractorBuilder)
  }

  // regex to extract fields from rule
  private val fieldPattern = """(?s)(\w+)\s*:\s*(\w+|\{\{.*\}\})""".r
}

class NamedExtractor(val name: String, val priority: Priority, val extractor: Extractor, val action: Action) {
  def extractFrom(document: Document, state: State): Seq[Mention] = {
    document.sentences.zipWithIndex flatMap {
      case (sentence, i) => extractor.findAllIn(sentence, state) flatMap {
        x => action(document, i, state, x)
      }
    }
  }

  def startsAt: Int = priority match {
    case ExactPriority(i) => i
    case IntervalPriority(start, end) => start
    case FromPriority(from) => from
  }
}