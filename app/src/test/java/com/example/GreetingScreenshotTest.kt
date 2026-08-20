package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.SpreadsheetTableGrid
import com.example.data.SpreadsheetRow
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val testCols = listOf("ID", "Name", "Category", "Price")
    val testRows = listOf(
        SpreadsheetRow(
            spreadsheetId = 1,
            rowIndex = 0,
            valuesJson = JSONArray(listOf("GDG-001", "Earbuds Pro", "Audio", "$129.99")).toString(),
            searchText = ""
        ),
        SpreadsheetRow(
            spreadsheetId = 1,
            rowIndex = 1,
            valuesJson = JSONArray(listOf("GDG-002", "Pixel Watch", "Wearables", "$249.50")).toString(),
            searchText = ""
        )
    )

    composeTestRule.setContent {
      MyApplicationTheme {
        SpreadsheetTableGrid(
            columns = testCols,
            rows = testRows,
            onRowClick = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
