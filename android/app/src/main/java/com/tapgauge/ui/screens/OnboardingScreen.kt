package com.tapgauge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class Page(val title: String, val body: String)

private val pages = listOf(
    Page("Know your tank\u2019s level by tapping it",
        "Tap your propane, water, or fuel tank with a knuckle or coin. TapGauge listens to the pitch and estimates how full it is."),
    Page("No sensor to buy or install \u2014 just your phone",
        "Other products make you attach a Bluetooth sensor. TapGauge uses only your phone\u2019s built-in microphone. Nothing to buy, nothing to stick on the tank."),
    Page("It gets more accurate as you use it",
        "TapGauge learns each tank when you tell it \u201cjust filled\u201d or \u201cjust ran out.\u201d It needs your microphone, and this is an estimate \u2014 not a certified safety gauge."),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(p.title, style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(p.body, style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pages.size) { i ->
                val active = i == pagerState.currentPage
                Box(
                    Modifier.padding(4.dp).size(if (active) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Gray),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDone) { Text("Skip") }
            Button(onClick = {
                if (pagerState.currentPage < pages.size - 1) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else onDone()
            }) {
                Text(if (pagerState.currentPage < pages.size - 1) "Next" else "Get started")
            }
        }
    }
}
