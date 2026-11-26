package com.aceinandroid.inkflow.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Minimalist editorial launch splash overlay for Inkflow.
 * Automatically skipped in E-INK mode or when opening a book directly via external intent.
 */
@Composable
fun InkflowSplashOverlay(
    skip: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by remember(skip) { mutableStateOf(!skip) }

    LaunchedEffect(skip) {
        if (!skip) {
            delay(750)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(animationSpec = tween(durationMillis = 260)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFF6F8FC), Color(0xFFECF2FC)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Blue Inkflow emblem badge matching the launcher icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF2B6EF3), Color(0xFF0E2772)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(44.dp)) {
                        val w = size.width
                        val h = size.height
                        val primaryWave = Path().apply {
                            moveTo(w * 0.10f, h * 0.50f)
                            cubicTo(
                                w * 0.32f, h * 0.20f,
                                w * 0.62f, h * 0.68f,
                                w * 0.90f, h * 0.34f,
                            )
                        }
                        val secondaryWave = Path().apply {
                            moveTo(w * 0.18f, h * 0.68f)
                            cubicTo(
                                w * 0.40f, h * 0.42f,
                                w * 0.66f, h * 0.84f,
                                w * 0.86f, h * 0.56f,
                            )
                        }
                        drawPath(
                            path = primaryWave,
                            color = Color.White,
                            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round),
                        )
                        drawPath(
                            path = secondaryWave,
                            color = Color(0xFFBFDCFF),
                            style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "Inkflow",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                    color = Color(0xFF0F172A),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A modern reader, elevated by AI.",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.4.sp,
                    color = Color(0xFF52637A),
                )
            }
        }
    }
}
