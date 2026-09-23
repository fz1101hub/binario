package it.binario.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryControlsAndPlannedBoardStateRemainAccessibleAtLargeFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                BinarioTheme {
                    OperationsSectionCard(
                        modifier = androidx.compose.ui.Modifier,
                        section = OperationsSection.TRAINS,
                        selected = true,
                        onClick = {}
                    )
                    StationBoardEntryRow(
                        entry = StationBoardEntry(
                            runId = TrainRunId("test-run"),
                            scheduledTime = ZonedDateTime.of(2026, 9, 23, 9, 30, 0, 0, ZoneId.of("Europe/Rome")),
                            destinationOrOrigin = StationId("destination"),
                            destinationOrOriginLabel = "Milano Centrale",
                            publicNumber = "1234"
                        ),
                        kind = BoardKind.DEPARTURES
                    )
                }
            }
        }

        composeRule.onNodeWithText("Treni").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("Per Milano Centrale. Orario programmato 09:30. Orario programmato; stato live non disponibile. Binario non disponibile. Treno 1234.")
            .fetchSemanticsNode()
    }
}
