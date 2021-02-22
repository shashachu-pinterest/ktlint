package com.pinterest.ktlint.ruleset.experimental

import com.pinterest.ktlint.core.Rule
import com.pinterest.ktlint.core.ast.*
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.psiUtil.children
import org.jetbrains.kotlin.psi.psiUtil.endOffset

/**
 * Ensures annotations occur immediately prior to the annotated construct
 *
 * https://kotlinlang.org/docs/reference/coding-conventions.html#annotation-formatting
 */
class AnnotationSpacingRule : Rule("annotation-spacing") {

    companion object {
        const val ERROR_MESSAGE = "Annotations should occur immediately before the annotated construct"
    }

    override fun visit(
        node: ASTNode,
        autoCorrect: Boolean,
        emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> Unit
    ) {
        if (node.elementType != ElementType.MODIFIER_LIST && node.elementType != ElementType.FILE_ANNOTATION_LIST) {
            return
        }

        val annotations =
            node.children()
                .mapNotNull { it.psi as? KtAnnotationEntry }
                .toList()
        if (annotations.isEmpty()) {
            return
        }

        // Join the nodes that immediately follow the annotations (whitespace), then add the final whitespace
        // if it's not a child of root. This happens when a new line separates the annotations from the annotated
        // construct. In the following example, there are no whitespace children of root, but root's next sibling is the
        // new line whitespace.
        //
        //      @JvmField
        //      val s: Any
        //
        val whiteSpaces = (annotations.asSequence().map { it.nextSibling } + node.treeNext)
            .filterIsInstance<PsiWhiteSpace>()
            .take(annotations.size)
            .toList()

        val next = node.nextSiblingWithAtLeastOneOf(
            {
                !it.isWhiteSpace() &&
                    it.textLength > 0 &&
                    !it.isPartOf(ElementType.FILE_ANNOTATION_LIST)
            },
            {
                // Disallow multiple white spaces as well as comments
                if (it.psi is PsiWhiteSpace) {
                    val s = it.text
                    // Ensure at least one occurrence of two line breaks
                    s.indexOf("\n") != s.lastIndexOf("\n")
                } else it.isPartOfComment()
            }
        )
        if (next != null) {
            if (node.elementType != ElementType.FILE_ANNOTATION_LIST) {
                val psi = node.psi
                emit(psi.endOffset - 1, ERROR_MESSAGE, true)
                if (autoCorrect) {
                    // Special-case autocorrection when the annotation is separated from the annotated construct
                    // by a comment: we need to swap the order of the comment and the annotation
                    if (next.isPartOfComment()) {
                        // Remove the annotation and the following whitespace
                        val nextSibling = node.nextSibling { it.isWhiteSpace() }
                        node.treeParent.removeChild(node)
                        nextSibling?.treeParent?.removeChild(nextSibling)
                        // Insert the annotation prior to the annotated construct
                        val space = PsiWhiteSpaceImpl("\n")
                        next.treeParent.addChild(space, next.nextCodeSibling())
                        next.treeParent.addChild(node, space)
                    } else {
                        removeExtraLineBreaks(node)
                    }
                }
            }
        }
        if (whiteSpaces.isNotEmpty() && annotations.size > 1 && node.elementType != ElementType.FILE_ANNOTATION_LIST) {
            // Check to make sure there are multi breaks between annotations
            if (whiteSpaces.any { psi -> psi.textToCharArray().filter { it == '\n' }.count() > 1 }) {
                val psi = node.psi
                emit(psi.endOffset - 1, ERROR_MESSAGE, true)
                if (autoCorrect) {
                    removeIntraLineBreaks(node, annotations.last())
                }
            }
        }
    }

    private inline fun ASTNode.nextSiblingWithAtLeastOneOf(
        p: (ASTNode) -> Boolean,
        needsToOccur: (ASTNode) -> Boolean
    ): ASTNode? {
        var n = this.treeNext
        var occurrenceCount = 0
        while (n != null) {
            if (needsToOccur(n)) {
                occurrenceCount++
            }
            if (p(n)) {
                return if (occurrenceCount > 0) {
                    n
                } else {
                    null
                }
            }
            n = n.treeNext
        }
        return null
    }

    private fun ASTNode?.isWhiteSpaceWithMultipleNewlines() =
        this != null && elementType == ElementType.WHITE_SPACE && text.contains("\n\n")

    private fun removeExtraLineBreaks(node: ASTNode) {
        val next = node.nextSibling {
            it.isWhiteSpaceWithNewline()
        } as? LeafPsiElement
        if (next != null) {
            rawReplaceExtraLineBreaks(next)
        }
    }

    private fun rawReplaceExtraLineBreaks(leaf: LeafPsiElement) {
        // Replace the extra white space with a single break
        val text = leaf.text
        val firstIndex = text.indexOf("\n") + 1
        val replacementText = text.substring(0, firstIndex) +
            text.substringAfter("\n").replace("\n", "")

        leaf.rawReplaceWithText(replacementText)
    }

    private fun removeIntraLineBreaks(
        node: ASTNode,
        last: KtAnnotationEntry
    ) {
        val txt = node.text
        // Pull the next before raw replace or it will blow up
        val lNext = node.nextLeaf()
        if (node is PsiWhiteSpaceImpl) {
            if (txt.toCharArray().count { it == '\n' } > 1) {
                rawReplaceExtraLineBreaks(node)
            }
        }

        if (lNext != null && !last.text.endsWith(lNext.text)) {
            removeIntraLineBreaks(lNext, last)
        }
    }
}
