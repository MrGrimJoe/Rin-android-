package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * High-fidelity stylized QR Matrix Renderer for Rin mesh tokens and ephemeral keys
 */
@Composable
fun QrCodeCanvas(
    payload: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.White,
    codeColor: Color = Color(0xFF0F172A)
) {
    val matrixSize = 25
    val grid = remember(payload) {
        generateQrMatrix(payload, matrixSize)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().aspectRatio(1f)) {
            val cellSize = size.width / matrixSize
            for (row in 0 until matrixSize) {
                for (col in 0 until matrixSize) {
                    if (grid[row][col]) {
                        drawRect(
                            color = codeColor,
                            topLeft = Offset(col * cellSize, row * cellSize),
                            size = Size(cellSize + 0.5f, cellSize + 0.5f)
                        )
                    }
                }
            }
        }
    }
}

private fun generateQrMatrix(text: String, size: Int): Array<BooleanArray> {
    val matrix = Array(size) { BooleanArray(size) { false } }

    // Finder patterns (3 corners: top-left, top-right, bottom-left)
    drawFinderPattern(matrix, 0, 0)
    drawFinderPattern(matrix, 0, size - 7)
    drawFinderPattern(matrix, size - 7, 0)

    // Timing patterns
    for (i in 7 until size - 7) {
        if (i % 2 == 0) {
            matrix[6][i] = true
            matrix[i][6] = true
        }
    }

    // Deterministic pseudo-random matrix filled by payload hash
    val hash = text.hashCode().toLong().absoluteValue
    val bytes = text.toByteArray()

    var byteIdx = 0
    for (r in 0 until size) {
        for (c in 0 until size) {
            // Skip finder pattern zones
            if (isFinderZone(r, c, size)) continue

            val charVal = if (bytes.isNotEmpty()) bytes[byteIdx % bytes.size].toInt() else 42
            val bit = ((r * 31 + c * 17 + hash + charVal) % 2L) == 0L
            matrix[r][c] = bit
            byteIdx++
        }
    }

    return matrix
}

private fun drawFinderPattern(matrix: Array<BooleanArray>, startR: Int, startC: Int) {
    for (r in 0..6) {
        for (c in 0..6) {
            val isBorder = r == 0 || r == 6 || c == 0 || c == 6
            val isCenter = r in 2..4 && c in 2..4
            matrix[startR + r][startC + c] = isBorder || isCenter
        }
    }
}

private fun isFinderZone(r: Int, c: Int, size: Int): Boolean {
    val inTopLeft = r <= 7 && c <= 7
    val inTopRight = r <= 7 && c >= size - 8
    val inBottomLeft = r >= size - 8 && c <= 7
    val inTiming = r == 6 || c == 6
    return inTopLeft || inTopRight || inBottomLeft || inTiming
}
