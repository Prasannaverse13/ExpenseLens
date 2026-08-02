package com.expenselens.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expenselens.ui.common.ExpenseLensPrimaryButton
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.theme.Emerald800
import com.expenselens.ui.theme.Emerald500
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val body: String,
    val gradientTop: Color,
    val gradientBottom: Color
)

private val pages = listOf(
    OnboardingPage(
        title = "Track every rupee,\neffortlessly.",
        body = "Take control of your finances with AI-powered clarity and simple expense logging.",
        gradientTop = Color(0xFFA0D0C2),
        gradientBottom = Color(0xFFFEBF8C)
    ),
    OnboardingPage(
        title = "Snap it, save it,\nforget it.",
        body = "Point your camera at any bill and let ExpenseLens extract the details — vendor, total, line items.",
        gradientTop = Color(0xFFB6EEDA),
        gradientBottom = Color(0xFFD7CCE8)
    ),
    OnboardingPage(
        title = "See the story\nbehind every spend.",
        body = "Beautiful dashboards and category breakdowns show you where your money goes each month.",
        gradientTop = Color(0xFFBFE0EE),
        gradientBottom = Color(0xFFFEBF8C)
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    onSkip: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }
    val scope = rememberCoroutineScope()

    GrainientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp)
        ) {
            // Top bar: Skip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .clickable { onSkip() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { idx ->
                OnboardingPageView(pages[idx])
            }

            // Page indicator dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pages.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(if (active) 24.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }

            // Get Started
            ExpenseLensPrimaryButton(
                text = if (pagerState.currentPage == pages.lastIndex) "Get Started" else "Next",
                onClick = {
                    if (pagerState.currentPage == pages.lastIndex) {
                        onGetStarted()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.padding(bottom = 32.dp),
                trailing = {
                    Text(
                        text = "→",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            )

            // "I already have an account"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row {
                    Text(
                        text = "I already have an account",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .clickable { onSkip() }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageView(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Illustrated hero — a soft, glassy card with an abstract phone
        Box(
            modifier = Modifier
                .size(width = 260.dp, height = 280.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(page.gradientTop, page.gradientBottom)
                    )
                )
        ) {
            // Decorative inner blob
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
            )
        }

        Spacer(Modifier.height(40.dp))

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 34.sp
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
