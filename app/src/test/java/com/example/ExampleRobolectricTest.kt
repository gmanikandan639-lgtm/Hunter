package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ExcelParser
import com.example.data.Spreadsheet
import com.example.data.SpreadsheetRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Sheet Search", appName)
  }

  @Test
  fun `spreadsheetRow matches keywords and parses values`() {
    val row = SpreadsheetRow(
      id = 1,
      spreadsheetId = 10,
      rowIndex = 0,
      valuesJson = "[\"DEV-101\",\"Quantum Keyboard\",\"Keyboards\",\"$149.99\",\"In Stock\"]",
      searchText = "dev-101 quantum keyboard keyboards $149.99 in stock"
    )

    assertTrue(row.searchText.contains("quantum", ignoreCase = true))
    assertTrue(row.searchText.contains("149.99", ignoreCase = true))
    assertFalse(row.searchText.contains("monitor", ignoreCase = true))

    val values = row.getValues()
    assertEquals(5, values.size)
    assertEquals("Quantum Keyboard", values[1])
  }

  @Test
  fun `excelParser parses standard and quoted CSV text`() {
    val csv = """ID,Name,Price,Notes
101,"Smart Watch, Pro",99.99,"Waterproof ""IP68"" rating"
102,Fitness Band,29.99,Basic model"""

    val parsed = ExcelParser.parseCsvText(csv)
    assertEquals(3, parsed.size)
    assertEquals(listOf("ID", "Name", "Price", "Notes"), parsed[0])
    assertEquals("Smart Watch, Pro", parsed[1][1])
    assertEquals("Waterproof \"IP68\" rating", parsed[1][3])
    assertEquals("102", parsed[2][0])
  }

  @Test
  fun `excelParser parses tab delimited TSV text`() {
    val tsv = "Product\tCategory\tStock\nMouse\tPeripherals\t50\nMonitor\tDisplays\t20"
    val parsed = ExcelParser.parseCsvText(tsv)
    assertEquals(3, parsed.size)
    assertEquals(listOf("Product", "Category", "Stock"), parsed[0])
    assertEquals("Mouse", parsed[1][0])
    assertEquals("Displays", parsed[2][1])
  }

  @Test
  fun `excelParser parseAny parses XML Spreadsheet 2003 XLS`() {
    val xmlXls = """<?xml version="1.0"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet">
  <Worksheet ss:Name="Sheet1">
    <Table>
      <Row>
        <Cell><Data ss:Type="String">Item</Data></Cell>
        <Cell><Data ss:Type="String">Qty</Data></Cell>
      </Row>
      <Row>
        <Cell><Data ss:Type="String">Desk Lamp</Data></Cell>
        <Cell><Data ss:Type="Number">15</Data></Cell>
      </Row>
    </Table>
  </Worksheet>
</Workbook>"""

    val stream = ByteArrayInputStream(xmlXls.toByteArray(Charsets.UTF_8))
    val parsed = ExcelParser.parseAny(stream, "inventory.xls")
    assertEquals(2, parsed.size)
    assertEquals(listOf("Item", "Qty"), parsed[0])
    assertEquals(listOf("Desk Lamp", "15"), parsed[1])
  }

  @Test
  fun `excelParser parseAny parses HTML table XLS format`() {
    val htmlXls = """<html><body>
<table>
  <tr><th>Employee</th><th>Department</th><th>Status</th></tr>
  <tr><td>Sarah Connor</td><td>Security</td><td>Active</td></tr>
  <tr><td>John Matrix</td><td>Operations</td><td>Active</td></tr>
</table>
</body></html>"""

    val stream = ByteArrayInputStream(htmlXls.toByteArray(Charsets.UTF_8))
    val parsed = ExcelParser.parseAny(stream, "staff_export.xls")
    assertEquals(3, parsed.size)
    assertEquals(listOf("Employee", "Department", "Status"), parsed[0])
    assertEquals("Sarah Connor", parsed[1][0])
    assertEquals("Operations", parsed[2][1])
  }

  @Test
  fun `spreadsheet entity supports metadata and versions`() {
    val sheet = Spreadsheet(
      id = 1,
      name = "Sales 2026",
      columnNamesJson = "[\"Region\",\"Sales\"]",
      fileFormat = "XLSX",
      rowCount = 45,
      versionNumber = 2
    )

    assertEquals(listOf("Region", "Sales"), sheet.getColumnNames())
    assertEquals("XLSX", sheet.fileFormat)
    assertEquals("v2", sheet.displayVersion)
  }
}
