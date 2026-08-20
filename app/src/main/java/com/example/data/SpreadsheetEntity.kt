package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

@Entity(tableName = "spreadsheets")
data class Spreadsheet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val importedAt: Long = System.currentTimeMillis(),
    val columnNamesJson: String, // JSON array of column headers
    val fileFormat: String = "CSV", // "CSV", "XLSX", "XLS", "PASTED", "TEMPLATE"
    val rowCount: Int = 0,
    val versionNumber: Int = 1,
    val parentGroupId: String? = null, // Used to group revisions of the same spreadsheet
    val updatedAt: Long = importedAt
) {
    fun getColumnNames(): List<String> {
        return try {
            val array = JSONArray(columnNamesJson)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    val displayVersion: String
        get() = "v$versionNumber"
}

@Entity(
    tableName = "spreadsheet_rows",
    foreignKeys = [
        ForeignKey(
            entity = Spreadsheet::class,
            parentColumns = ["id"],
            childColumns = ["spreadsheetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("spreadsheetId"),
        Index("searchText")
    ]
)
data class SpreadsheetRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spreadsheetId: Long,
    val rowIndex: Int,
    val valuesJson: String, // JSON array of row values
    val searchText: String // Concatenated lowercase row values for search
) {
    fun getValues(): List<String> {
        return try {
            val array = JSONArray(valuesJson)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
