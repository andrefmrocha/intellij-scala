package org.jetbrains.plugins.scala
package editor

import com.intellij.psi.{PsiElement, PsiErrorElement, PsiFile}
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScPatternDefinition, ScVariableDefinition}

import scala.util.matching.Regex

object AutoBraceUtils {
  def nextExpressionInIndentationContext(element: PsiElement): Option[ScExpression] = {
    element.nextSibling match {
      case Some(e) => toIndentedExpression(e)
      case _ => None
    }
  }

  def previousExpressionInIndentationContext(element: PsiElement): Option[ScExpression] = {

    val orgStartOffset = element.endOffset
    val lastRealElement = PsiTreeUtil.prevLeaf(element)

    if (lastRealElement.is[PsiErrorElement]) {
      None
    } else
      lastRealElement
        .withParentsInFile
        .takeWhile(e => !e.is[ScBlock] && e.endOffset <= orgStartOffset)
        .flatMap(toIndentedExpression)
        .headOption
  }


  private def toIndentedExpression(element: PsiElement): Option[ScExpression] = element match {
    case expr: ScExpression if AutoBraceUtils.isIndentationContext(element) && isPrecededByIndent(element) =>
      Some(expr)
    case _ =>
      None
  }

  private def isPrecededByIndent(element: PsiElement): Boolean = {
    element.getPrevSibling.nullSafe.exists(_.textContains('\n'))
  }

  def isIndentationContext(element: PsiElement): Boolean = element.getParent match {
    case ScReturn(`element`) => true
    case ScIf(_, thenBranch, elseBranch) if thenBranch.contains(element) || elseBranch.contains(element) => true
    case ScWhile(_, Some(`element`)) => true
    case ScPatternDefinition.expr(`element`) => true
    case ScVariableDefinition.expr(`element`) => true
    case ScFunctionDefinition.withBody(`element`) => true
    case ScFor(_, `element`) => true
    case ScTry(Some(`element`), _, _) => true
    case ScFinallyBlock(`element`) => true
    case _ => false
  }

  private val ifIndentationContextContinuation = Set(ScalaTokenTypes.kELSE)
  private val tryIndentationContextContinuation = Set(ScalaTokenTypes.kCATCH, ScalaTokenTypes.kFINALLY)

  def indentationContextContinuation(element: PsiElement): Set[IElementType] = element.getParent match {
    case ScIf(_, Some(`element`), _) => ifIndentationContextContinuation
    case ScTry(Some(`element`), _, _) => tryIndentationContextContinuation
    case _ => Set.empty
  }

  def canBeContinuedWith(element: PsiElement, continuationChar: Char): Boolean = element match {
    case ScIf(_, _, None) if continuationChar == 'e' => true
    case ScTry(_, None, None) if continuationChar == 'c' => true
    case ScTry(_, _, None) if continuationChar == 'f'  => true
    case _ => false
  }


  val indentationContextContinuationsElements: Set[IElementType] = Set(
    ScalaTokenTypes.kELSE,
    ScalaTokenTypes.kCATCH,
    ScalaTokenTypes.kFINALLY
  )

  def continuesConstructAfterIndentationContext(elem: PsiElement): Boolean =
    indentationContextContinuationsElements.contains(elem.elementType)


  val indentationContextContinuationsTexts: Set[String] =
    indentationContextContinuationsElements.map(_.toString)

  def continuesConstructAfterIndentationContext(elementText: String): Boolean =
    indentationContextContinuationsTexts.contains(elementText)


  private val continuationPrefixRegexPattern = new Regex(
    indentationContextContinuationsTexts
      .iterator
      .map(_.foldRight("")("(" + _ + _ + ")?"))
      .mkString("|")
  ).pattern

  def couldBeContinuationAfterIndentationContext(keywordPrefix: String): Boolean = {
    val matcher = continuationPrefixRegexPattern.matcher(keywordPrefix)
    matcher.matches()
  }
}
