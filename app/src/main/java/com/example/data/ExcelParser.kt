package com.example.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

object ExcelParser {

    /**
     * Unified entry point to parse any spreadsheet format:
     * - XLSX (.xlsx, OpenXML)
     * - XLS (.xls, BIFF8 binary, XML Spreadsheet 2003, HTML table, or tab-delimited)
     * - CSV / TSV / Delimited text (.csv, .tsv, .txt)
     */
    fun parseAny(inputStream: InputStream, filename: String = ""): List<List<String>> {
        val bytes = inputStream.readBytes()
        if (bytes.isEmpty()) return emptyList()

        val lowerName = filename.lowercase()

        // 1. Check for Zip/XLSX signature: 'P', 'K', 0x03, 0x04
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) {
            return parseXlsx(ByteArrayInputStream(bytes))
        }

        // 2. Check for OLE2 / CFBF binary XLS signature: D0 CF 11 E0 A1 B1 1A E1
        if (bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0xD0 && (bytes[1].toInt() and 0xFF) == 0xCF &&
            (bytes[2].toInt() and 0xFF) == 0x11 && (bytes[3].toInt() and 0xFF) == 0xE0 &&
            (bytes[4].toInt() and 0xFF) == 0xA1 && (bytes[5].toInt() and 0xFF) == 0xB1 &&
            (bytes[6].toInt() and 0xFF) == 0x1A && (bytes[7].toInt() and 0xFF) == 0xE1) {
            val rows = parseBinaryXls(bytes)
            if (rows.isNotEmpty()) return rows
        }

        // 3. Check for text-based formats (XML Spreadsheet 2003, HTML Table, CSV/TSV)
        val textSnippet = try {
            val len = minOf(bytes.size, 4096)
            String(bytes, 0, len, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            ""
        }

        // Check for XML Spreadsheet 2003 (common .xls export format)
        if (textSnippet.contains("urn:schemas-microsoft-com:office:spreadsheet") ||
            (textSnippet.startsWith("<?xml") && textSnippet.contains("<Workbook"))) {
            val rows = parseXmlSpreadsheet2003(ByteArrayInputStream(bytes))
            if (rows.isNotEmpty()) return rows
        }

        // Check for HTML Table export with .xls extension
        if (textSnippet.contains("<table", ignoreCase = true) &&
            textSnippet.contains("<tr", ignoreCase = true)) {
            val fullHtml = String(bytes, Charsets.UTF_8)
            val rows = parseHtmlTable(fullHtml)
            if (rows.isNotEmpty()) return rows
        }

        // 4. Default: Parse as Delimited Text (CSV, TSV, Semicolon, Pipe)
        val fullText = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
        return parseCsvText(fullText)
    }

    // ==========================================
    // 1. XLSX (OpenXML) Parser
    // ==========================================

    fun parseXlsx(inputStream: InputStream): List<List<String>> {
        val sharedStrings = mutableListOf<String>()
        var sheetBytes: ByteArray? = null
        var sharedStringsBytes: ByteArray? = null

        try {
            val zip = ZipInputStream(inputStream)
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "xl/sharedStrings.xml") {
                    sharedStringsBytes = zip.readBytes()
                } else if (name == "xl/worksheets/sheet1.xml" || (sheetBytes == null && name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))) {
                    sheetBytes = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (sharedStringsBytes != null) {
            parseSharedStrings(sharedStringsBytes.inputStream(), sharedStrings)
        }

        if (sheetBytes != null) {
            return parseSheet(sheetBytes.inputStream(), sharedStrings)
        }

        return emptyList()
    }

    private fun parseSharedStrings(inputStream: InputStream, list: MutableList<String>) {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(inputStream, "UTF-8")
            var eventType = parser.eventType
            var inT = false
            val currentText = StringBuilder()
            val currentItem = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "si") {
                            currentItem.setLength(0)
                        } else if (name == "t") {
                            inT = true
                            currentText.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inT) {
                            currentText.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "t") {
                            inT = false
                            currentItem.append(currentText)
                        } else if (name == "si") {
                            list.add(currentItem.toString())
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseSheet(inputStream: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rowsList = mutableListOf<MutableList<String>>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(inputStream, "UTF-8")
            var eventType = parser.eventType

            var rowIndex = -1
            var colIndex = -1
            var cellType = ""
            var inInlineStr = false
            val currentText = StringBuilder()
            val tempRows = mutableMapOf<Int, MutableMap<Int, String>>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "row") {
                            val rAttr = parser.getAttributeValue(null, "r")
                            rowIndex = (rAttr?.toIntOrNull() ?: (rowIndex + 1)) - 1
                        } else if (name == "c") {
                            val rRef = parser.getAttributeValue(null, "r") ?: ""
                            colIndex = colRefToNum(rRef)
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            currentText.setLength(0)
                        } else if (name == "is" || name == "inlineStr") {
                            inInlineStr = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        currentText.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "v" || (inInlineStr && name == "t")) {
                            val rawVal = currentText.toString().trim()
                            val cellValue = if (cellType == "s") {
                                val stringIndex = rawVal.toIntOrNull() ?: -1
                                if (stringIndex in sharedStrings.indices) {
                                    sharedStrings[stringIndex]
                                } else {
                                    rawVal
                                }
                            } else if (cellType == "b") {
                                if (rawVal == "1") "TRUE" else "FALSE"
                            } else {
                                rawVal
                            }
                            if (rowIndex >= 0 && colIndex >= 0) {
                                val rMap = tempRows.getOrPut(rowIndex) { mutableMapOf() }
                                rMap[colIndex] = cellValue
                            }
                        } else if (name == "is" || name == "inlineStr") {
                            inInlineStr = false
                        }
                    }
                }
                eventType = parser.next()
            }

            if (tempRows.isNotEmpty()) {
                val maxRow = tempRows.keys.maxOrNull() ?: 0
                val maxCol = tempRows.values.flatMap { it.keys }.maxOrNull() ?: 0

                for (r in 0..maxRow) {
                    val rMap = tempRows[r]
                    if (rMap == null) {
                        rowsList.add(MutableList(maxCol + 1) { "" })
                    } else {
                        val rowVals = mutableListOf<String>()
                        for (c in 0..maxCol) {
                            rowVals.add(rMap[c] ?: "")
                        }
                        rowsList.add(rowVals)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return normalizeRows(rowsList)
    }

    private fun colRefToNum(ref: String): Int {
        var col = 0
        for (char in ref) {
            if (char in 'A'..'Z') {
                col = col * 26 + (char - 'A' + 1)
            } else {
                break
            }
        }
        return col - 1
    }

    // ==========================================
    // 2. Binary XLS (BIFF8 / OLE2) Parser
    // ==========================================

    private fun parseBinaryXls(bytes: ByteArray): List<List<String>> {
        try {
            val workbookStream = extractWorkbookFromOle2(bytes) ?: return emptyList()
            return parseBiffStream(workbookStream)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun extractWorkbookFromOle2(bytes: ByteArray): ByteArray? {
        if (bytes.size < 512) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val sectorSizePower = buffer.getShort(30).toInt()
        val sectorSize = 1 shl sectorSizePower
        val dirFirstSector = buffer.getInt(48)
        val numFatSectors = buffer.getInt(44)

        // Read the MSAT / FAT sector lookup
        val fatSectors = IntArray(numFatSectors)
        val maxHeaderFat = minOf(numFatSectors, 109)
        for (i in 0 until maxHeaderFat) {
            fatSectors[i] = buffer.getInt(76 + i * 4)
        }

        // Build the FAT table
        val entriesPerSector = sectorSize / 4
        val fatTable = IntArray(numFatSectors * entriesPerSector)
        for (i in 0 until numFatSectors) {
            val sec = fatSectors[i]
            if (sec < 0) continue
            val offset = (sec + 1) * sectorSize
            if (offset + sectorSize <= bytes.size) {
                for (j in 0 until entriesPerSector) {
                    fatTable[i * entriesPerSector + j] = buffer.getInt(offset + j * 4)
                }
            }
        }

        // Navigate directory sectors to find "Workbook" or "Book"
        var currentDirSec = dirFirstSector
        val visitedSecs = mutableSetOf<Int>()

        while (currentDirSec >= 0 && currentDirSec < (bytes.size / sectorSize) && !visitedSecs.contains(currentDirSec)) {
            visitedSecs.add(currentDirSec)
            val dirOffset = (currentDirSec + 1) * sectorSize
            val numEntriesInSector = sectorSize / 128

            for (entryIndex in 0 until numEntriesInSector) {
                val entryOffset = dirOffset + entryIndex * 128
                if (entryOffset + 128 > bytes.size) break

                val nameLength = buffer.getShort(entryOffset + 64).toInt()
                if (nameLength > 2) {
                    val nameChars = CharArray((nameLength / 2) - 1)
                    for (c in nameChars.indices) {
                        nameChars[c] = buffer.getChar(entryOffset + c * 2)
                    }
                    val entryName = String(nameChars)

                    if (entryName.equals("Workbook", ignoreCase = true) || entryName.equals("Book", ignoreCase = true)) {
                        val startSector = buffer.getInt(entryOffset + 116)
                        val streamSize = buffer.getInt(entryOffset + 120)

                        if (streamSize > 0 && startSector >= 0) {
                            return readStreamSectors(bytes, sectorSize, fatTable, startSector, streamSize)
                        }
                    }
                }
            }

            // Follow FAT chain for next directory sector
            currentDirSec = if (currentDirSec in fatTable.indices) fatTable[currentDirSec] else -2
            if (currentDirSec == -2 || currentDirSec == -1) break // ENDOFCHAIN
        }

        return null
    }

    private fun readStreamSectors(bytes: ByteArray, sectorSize: Int, fatTable: IntArray, startSector: Int, streamSize: Int): ByteArray {
        val result = ByteArray(streamSize)
        var currentSec = startSector
        var written = 0
        val visited = mutableSetOf<Int>()

        while (currentSec >= 0 && written < streamSize && !visited.contains(currentSec)) {
            visited.add(currentSec)
            val offset = (currentSec + 1) * sectorSize
            val bytesToCopy = minOf(sectorSize, streamSize - written)
            if (offset + bytesToCopy <= bytes.size) {
                System.arraycopy(bytes, offset, result, written, bytesToCopy)
                written += bytesToCopy
            } else {
                break
            }

            currentSec = if (currentSec in fatTable.indices) fatTable[currentSec] else -2
            if (currentSec == -2 || currentSec == -1) break
        }

        return result
    }

    private fun parseBiffStream(streamBytes: ByteArray): List<List<String>> {
        val buffer = ByteBuffer.wrap(streamBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sst = mutableListOf<String>()
        val cellMap = mutableMapOf<Int, MutableMap<Int, String>>()

        var pos = 0
        while (pos + 4 <= streamBytes.size) {
            val recordId = buffer.getShort(pos).toInt() and 0xFFFF
            val recordLen = buffer.getShort(pos + 2).toInt() and 0xFFFF
            val dataPos = pos + 4

            if (dataPos + recordLen > streamBytes.size) break

            when (recordId) {
                0x00FC -> { // SST (Shared String Table)
                    parseBiffSst(buffer, dataPos, recordLen, sst)
                }
                0x00FD -> { // LABELSST
                    if (recordLen >= 10) {
                        val row = buffer.getShort(dataPos).toInt() and 0xFFFF
                        val col = buffer.getShort(dataPos + 2).toInt() and 0xFFFF
                        val sstIndex = buffer.getInt(dataPos + 6)
                        if (sstIndex in sst.indices) {
                            val rMap = cellMap.getOrPut(row) { mutableMapOf() }
                            rMap[col] = sst[sstIndex]
                        }
                    }
                }
                0x0204 -> { // LABEL (Direct string cell)
                    if (recordLen >= 8) {
                        val row = buffer.getShort(dataPos).toInt() and 0xFFFF
                        val col = buffer.getShort(dataPos + 2).toInt() and 0xFFFF
                        val strLen = buffer.getShort(dataPos + 6).toInt() and 0xFFFF
                        if (dataPos + 8 + strLen <= dataPos + recordLen) {
                            val str = String(streamBytes, dataPos + 8, strLen, Charsets.ISO_8859_1)
                            val rMap = cellMap.getOrPut(row) { mutableMapOf() }
                            rMap[col] = str
                        }
                    }
                }
                0x0203 -> { // NUMBER (64-bit IEEE double)
                    if (recordLen >= 14) {
                        val row = buffer.getShort(dataPos).toInt() and 0xFFFF
                        val col = buffer.getShort(dataPos + 2).toInt() and 0xFFFF
                        val num = buffer.getDouble(dataPos + 6)
                        val formattedNum = formatNumber(num)
                        val rMap = cellMap.getOrPut(row) { mutableMapOf() }
                        rMap[col] = formattedNum
                    }
                }
                0x027E -> { // RK (Compressed 32-bit number)
                    if (recordLen >= 10) {
                        val row = buffer.getShort(dataPos).toInt() and 0xFFFF
                        val col = buffer.getShort(dataPos + 2).toInt() and 0xFFFF
                        val rkVal = buffer.getInt(dataPos + 6)
                        val num = decodeRkNumber(rkVal)
                        val rMap = cellMap.getOrPut(row) { mutableMapOf() }
                        rMap[col] = formatNumber(num)
                    }
                }
                0x00BD -> { // MULRK
                    if (recordLen >= 6) {
                        val row = buffer.getShort(dataPos).toInt() and 0xFFFF
                        val firstCol = buffer.getShort(dataPos + 2).toInt() and 0xFFFF
                        val lastCol = buffer.getShort(dataPos + recordLen - 2).toInt() and 0xFFFF
                        var rkOffset = dataPos + 4
                        for (col in firstCol..lastCol) {
                            if (rkOffset + 6 <= dataPos + recordLen) {
                                val rkVal = buffer.getInt(rkOffset + 2)
                                val num = decodeRkNumber(rkVal)
                                val rMap = cellMap.getOrPut(row) { mutableMapOf() }
                                rMap[col] = formatNumber(num)
                                rkOffset += 6
                            }
                        }
                    }
                }
                0x0205 -> { // BOOLERR
                    if (recordLen >= 8) {
                        val row = buffer.getShort(dataPos).toInt() and 0xFFFF
                        val col = buffer.getShort(dataPos + 2).toInt() and 0xFFFF
                        val bVal = buffer.get(dataPos + 6).toInt()
                        val isErr = buffer.get(dataPos + 7).toInt()
                        if (isErr == 0) {
                            val rMap = cellMap.getOrPut(row) { mutableMapOf() }
                            rMap[col] = if (bVal != 0) "TRUE" else "FALSE"
                        }
                    }
                }
                0x000A -> { // EOF
                    // End of sheet or workbook
                }
            }

            pos += 4 + recordLen
        }

        if (cellMap.isEmpty()) return emptyList()

        val maxRow = cellMap.keys.maxOrNull() ?: 0
        val maxCol = cellMap.values.flatMap { it.keys }.maxOrNull() ?: 0
        val result = mutableListOf<List<String>>()

        for (r in 0..maxRow) {
            val rMap = cellMap[r]
            if (rMap == null) {
                result.add(List(maxCol + 1) { "" })
            } else {
                val rowVals = mutableListOf<String>()
                for (c in 0..maxCol) {
                    rowVals.add(rMap[c] ?: "")
                }
                result.add(rowVals)
            }
        }

        return normalizeRows(result)
    }

    private fun parseBiffSst(buffer: ByteBuffer, dataPos: Int, recordLen: Int, sst: MutableList<String>) {
        if (recordLen < 8) return
        val uniqueStrings = buffer.getInt(dataPos + 4)
        var offset = dataPos + 8
        val end = dataPos + recordLen

        var strCount = 0
        while (offset < end && strCount < uniqueStrings) {
            if (offset + 3 > end) break
            val charCount = buffer.getShort(offset).toInt() and 0xFFFF
            val flags = buffer.get(offset + 2).toInt()
            val is16Bit = (flags and 0x01) != 0
            val hasRichText = (flags and 0x08) != 0
            val hasExtString = (flags and 0x04) != 0

            var headerSize = 3
            if (hasRichText) headerSize += 2
            if (hasExtString) headerSize += 4

            val textStart = offset + headerSize
            val bytesPerChar = if (is16Bit) 2 else 1
            val textBytesLen = charCount * bytesPerChar

            if (textStart + textBytesLen <= end) {
                val str = if (is16Bit) {
                    val chars = CharArray(charCount)
                    for (c in 0 until charCount) {
                        chars[c] = buffer.getChar(textStart + c * 2)
                    }
                    String(chars)
                } else {
                    val bytes = ByteArray(charCount)
                    for (b in 0 until charCount) {
                        bytes[b] = buffer.get(textStart + b)
                    }
                    String(bytes, Charsets.ISO_8859_1)
                }
                sst.add(str)
                strCount++
                offset = textStart + textBytesLen
                if (hasRichText) {
                    val rtRuns = buffer.getShort(offset - textBytesLen - headerSize + 3).toInt() and 0xFFFF
                    offset += rtRuns * 4
                }
            } else {
                break
            }
        }
    }

    private fun decodeRkNumber(rk: Int): Double {
        val isInteger = (rk and 0x02) != 0
        val is100Divided = (rk and 0x01) != 0

        var num: Double = if (isInteger) {
            (rk shr 2).toDouble()
        } else {
            val bits = (rk.toLong() and 0xFFFFFFFC.toLong()) shl 32
            java.lang.Double.longBitsToDouble(bits)
        }

        if (is100Divided) {
            num /= 100.0
        }
        return num
    }

    private fun formatNumber(num: Double): String {
        return if (num == num.toLong().toDouble()) {
            num.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", num).trimEnd('0').trimEnd('.')
        }
    }

    // ==========================================
    // 3. XML Spreadsheet 2003 Parser (.xls)
    // ==========================================

    private fun parseXmlSpreadsheet2003(inputStream: InputStream): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(inputStream, "UTF-8")
            var eventType = parser.eventType

            val currentRow = mutableListOf<String>()
            val currentCell = StringBuilder()
            var inData = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name == "Row") {
                            currentRow.clear()
                        } else if (name == "Data") {
                            inData = true
                            currentCell.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inData) {
                            currentCell.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "Data") {
                            inData = false
                            currentRow.add(currentCell.toString().trim())
                        } else if (name == "Row") {
                            if (currentRow.isNotEmpty()) {
                                rows.add(ArrayList(currentRow))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return normalizeRows(rows)
    }

    // ==========================================
    // 4. HTML Table Parser (.xls)
    // ==========================================

    private fun parseHtmlTable(html: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val trRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tdRegex = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tagStripRegex = Regex("<[^>]+>")

        for (trMatch in trRegex.findAll(html)) {
            val trContent = trMatch.groupValues[1]
            val rowValues = mutableListOf<String>()
            for (tdMatch in tdRegex.findAll(trContent)) {
                val rawCell = tdMatch.groupValues[1]
                val cleanCell = tagStripRegex.replace(rawCell, "").trim()
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                rowValues.add(cleanCell)
            }
            if (rowValues.isNotEmpty() && rowValues.any { it.isNotBlank() }) {
                rows.add(rowValues)
            }
        }
        return normalizeRows(rows)
    }

    // ==========================================
    // 5. CSV / TSV / Delimited Text Parser
    // ==========================================

    fun parseCsvText(text: String): List<List<String>> {
        if (text.isBlank()) return emptyList()

        val lines = text.split(Regex("\\r?\\n")).filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val firstLine = lines.first()
        val commaCount = firstLine.count { it == ',' }
        val semicolonCount = firstLine.count { it == ';' }
        val tabCount = firstLine.count { it == '\t' }
        val pipeCount = firstLine.count { it == '|' }

        val delimiter = when {
            tabCount > commaCount && tabCount > semicolonCount && tabCount > pipeCount -> '\t'
            semicolonCount > commaCount && semicolonCount > tabCount && semicolonCount > pipeCount -> ';'
            pipeCount > commaCount && pipeCount > semicolonCount && pipeCount > tabCount -> '|'
            else -> ','
        }

        val parsedRows = mutableListOf<List<String>>()
        for (line in lines) {
            val row = mutableListOf<String>()
            val currentField = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val char = line[i]
                if (char == '"') {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                } else if (char == delimiter && !inQuotes) {
                    row.add(currentField.toString().trim())
                    currentField.setLength(0)
                } else {
                    currentField.append(char)
                }
                i++
            }
            row.add(currentField.toString().trim())
            parsedRows.add(row)
        }

        return normalizeRows(parsedRows)
    }

    private fun normalizeRows(rows: List<List<String>>): List<List<String>> {
        val filtered = rows.filter { row -> row.any { it.isNotBlank() } }
        if (filtered.isEmpty()) return emptyList()

        val maxCols = filtered.maxOfOrNull { it.size } ?: 0
        if (maxCols == 0) return emptyList()

        return filtered.map { row ->
            if (row.size < maxCols) {
                row + List(maxCols - row.size) { "" }
            } else {
                row
            }
        }
    }
}
