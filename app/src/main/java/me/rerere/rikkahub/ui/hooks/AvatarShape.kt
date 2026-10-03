package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import me.rerere.rikkahub.data.datastore.ChatAvatarShape
import kotlin.math.roundToInt

@Composable
fun rememberAvatarShape(
    loading: Boolean,
    idleShape: Shape = CircleShape,
): Shape {
    val infiniteTransition = rememberInfiniteTransition()
    val rotateAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 3000,
                easing = LinearEasing
            ),
        )
    )
    return if (loading) MaterialShapes.Cookie6Sided.toShape(rotateAngle.value.roundToInt()) else idleShape
}

fun ChatAvatarShape.toComposeAvatarShape(): Shape = when (this) {
    ChatAvatarShape.CIRCLE -> CircleShape
    ChatAvatarShape.ROUNDED_SQUARE -> RoundedCornerShape(percent = 15)
}
