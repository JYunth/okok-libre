package app.okoklibre.data

import android.content.ContentResolver
import android.net.Uri
import app.okoklibre.algo.Sex
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Outcome of a CSV import: successfully parsed rows plus per-line validation errors. */
data class CsvImportResult<T>(
    val rows: List<T>,
    val errors: List<String>,
) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * RFC-4180-ish CSV export/import of roles and weight records (CSV as the open export format).
 *
 * The core functions work on plain [OutputStream]/[InputStream] so they are
 * JVM-testable; the [ContentResolver]/[Uri] helpers are a thin Android shell.
 * Nullable fields serialize as empty strings; enums as their names; numbers
 * use locale-independent [Double.toString].
 */
object CsvExporter {

    const val ROLE_HEADER =
        "id,nickname,sex,birthdayEpochDay,heightCm,weightGoalKg,avatarPath,roleType,createdAtEpochSec"

    const val RECORD_HEADER =
        "id,roleId,weightKg,timestampEpochSec,bmi,bodyFatPct,boneKg,musclePct,waterPct," +
            "bmrKcal,bodyAge,visceralFat,score,idealWeightKg,heightCm,sex,ageYears," +
            "impedanceOhm,scaleProperty,scaleWeightString,productId,remark,heartRateBpm,deleted"

    // --- export ---

    fun exportRoles(roles: List<RoleEntity>, out: OutputStream) {
        out.writer().use { w ->
            w.line(ROLE_HEADER)
            for (r in roles) {
                w.line(
                    fields(
                        r.id, r.nickname, r.sex.name, r.birthdayEpochDay, r.heightCm,
                        r.weightGoalKg, r.avatarPath, r.roleType.name, r.createdAtEpochSec,
                    ),
                )
            }
        }
    }

    fun exportRecords(records: List<WeightRecordEntity>, out: OutputStream) {
        out.writer().use { w ->
            w.line(RECORD_HEADER)
            for (r in records) {
                w.line(
                    fields(
                        r.id, r.roleId, r.weightKg, r.timestampEpochSec, r.bmi, r.bodyFatPct,
                        r.boneKg, r.musclePct, r.waterPct, r.bmrKcal, r.bodyAge, r.visceralFat,
                        r.score, r.idealWeightKg, r.heightCm, r.sex.name, r.ageYears,
                        r.impedanceOhm, r.scaleProperty, r.scaleWeightString, r.productId,
                        r.remark, r.heartRateBpm, r.deleted,
                    ),
                )
            }
        }
    }

    // --- import ---

    fun importRoles(input: InputStream): CsvImportResult<RoleEntity> {
        val errors = mutableListOf<String>()
        val rows = mutableListOf<RoleEntity>()
        parse(input, ROLE_HEADER, errors) { f, line ->
            if (f.size != 9) {
                errors.add("line $line: expected 9 columns, got ${f.size}")
                return@parse
            }
            try {
                rows.add(
                    RoleEntity(
                        id = f[0].toLong(),
                        nickname = f[1].ifEmpty { error("empty nickname") },
                        sex = Sex.valueOf(f[2]),
                        birthdayEpochDay = f[3].toLong(),
                        heightCm = f[4].toInt(),
                        weightGoalKg = f[5].ifEmpty { null }?.toDouble(),
                        avatarPath = f[6].ifEmpty { null },
                        roleType = RoleType.valueOf(f[7]),
                        createdAtEpochSec = f[8].toLong(),
                    ),
                )
            } catch (e: Exception) {
                errors.add("line $line: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return CsvImportResult(rows, errors)
    }

    fun importRecords(input: InputStream): CsvImportResult<WeightRecordEntity> {
        val errors = mutableListOf<String>()
        val rows = mutableListOf<WeightRecordEntity>()
        parse(input, RECORD_HEADER, errors) { f, line ->
            if (f.size != 24) {
                errors.add("line $line: expected 24 columns, got ${f.size}")
                return@parse
            }
            try {
                rows.add(
                    WeightRecordEntity(
                        id = f[0].toLong(),
                        roleId = f[1].toLong(),
                        weightKg = f[2].toDouble(),
                        timestampEpochSec = f[3].toLong(),
                        bmi = f[4].toDouble(),
                        bodyFatPct = f[5].toDouble(),
                        boneKg = f[6].toDouble(),
                        musclePct = f[7].toDouble(),
                        waterPct = f[8].toDouble(),
                        bmrKcal = f[9].toDouble(),
                        bodyAge = f[10].toInt(),
                        visceralFat = f[11].toDouble(),
                        score = f[12].toInt(),
                        idealWeightKg = f[13].toDouble(),
                        heightCm = f[14].toDouble(),
                        sex = Sex.valueOf(f[15]),
                        ageYears = f[16].toInt(),
                        impedanceOhm = f[17].toDouble(),
                        scaleProperty = f[18].toInt(),
                        scaleWeightString = f[19].ifEmpty { null },
                        productId = f[20].ifEmpty { null }?.toLong(),
                        remark = f[21].ifEmpty { null },
                        heartRateBpm = f[22].ifEmpty { null }?.toInt(),
                        deleted = f[23].toBoolean(),
                    ),
                )
            } catch (e: Exception) {
                errors.add("line $line: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return CsvImportResult(rows, errors)
    }

    // --- Android shell (ContentResolver/Uri) ---

    fun exportRoles(resolver: ContentResolver, uri: Uri, roles: List<RoleEntity>) {
        resolver.openOutputStream(uri, "wt")?.use { exportRoles(roles, it) }
            ?: error("cannot open $uri for writing")
    }

    fun exportRecords(resolver: ContentResolver, uri: Uri, records: List<WeightRecordEntity>) {
        resolver.openOutputStream(uri, "wt")?.use { exportRecords(records, it) }
            ?: error("cannot open $uri for writing")
    }

    fun importRoles(resolver: ContentResolver, uri: Uri): CsvImportResult<RoleEntity> =
        resolver.openInputStream(uri)?.use { importRoles(it) }
            ?: CsvImportResult(emptyList(), listOf("cannot open $uri for reading"))

    fun importRecords(resolver: ContentResolver, uri: Uri): CsvImportResult<WeightRecordEntity> =
        resolver.openInputStream(uri)?.use { importRecords(it) }
            ?: CsvImportResult(emptyList(), listOf("cannot open $uri for reading"))

    // --- CSV mechanics ---

    private fun OutputStream.writer(): BufferedWriter =
        BufferedWriter(OutputStreamWriter(this, StandardCharsets.UTF_8))

    private fun BufferedWriter.line(text: String) {
        write(text)
        write("\r\n")
    }

    private fun fields(vararg values: Any?): String = values.joinToString(",") { escape(it) }

    private fun escape(value: Any?): String {
        val s = value?.toString() ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${s.replace("\"", "\"\"")}\""
        } else {
            s
        }
    }

    private fun parse(
        input: InputStream,
        expectedHeader: String,
        errors: MutableList<String>,
        onRow: (fields: List<String>, lineNumber: Int) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        val records = readRecords(reader)
        if (records.isEmpty()) {
            errors.add("empty file")
            return
        }
        if (records[0].joinToString(",") != expectedHeader) {
            errors.add("unexpected header: ${records[0].joinToString(",")}")
        }
        // Line numbers are approximate for rows containing embedded newlines.
        records.drop(1).forEachIndexed { index, fields ->
            if (fields.size == 1 && fields[0].isBlank()) return@forEachIndexed
            onRow(fields, index + 2)
        }
    }

    /** RFC-4180 record reader: quoted fields may contain commas, CRLFs and doubled quotes. */
    private fun readRecords(reader: BufferedReader): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        var field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false
        var sawAny = false

        fun endField() {
            fields.add(field.toString())
            field = StringBuilder()
            fieldStarted = false
        }

        fun endRecord() {
            endField()
            records.add(fields)
            fields = mutableListOf()
        }

        while (true) {
            val c = reader.read()
            if (c == -1) break
            sawAny = true
            val ch = c.toChar()
            when {
                inQuotes -> when (ch) {
                    '"' -> {
                        reader.mark(1)
                        val next = reader.read()
                        if (next == '"'.code) {
                            field.append('"')
                        } else {
                            inQuotes = false
                            if (next != -1) {
                                reader.reset()
                            }
                        }
                    }
                    else -> field.append(ch)
                }
                ch == '"' && !fieldStarted -> {
                    inQuotes = true
                    fieldStarted = true
                }
                ch == ',' -> endField().also { fieldStarted = false }
                ch == '\r' -> {
                    reader.mark(1)
                    val next = reader.read()
                    if (next != '\n'.code && next != -1) reader.reset()
                    endRecord()
                }
                ch == '\n' -> endRecord()
                else -> {
                    field.append(ch)
                    fieldStarted = true
                }
            }
        }
        if (sawAny && (field.isNotEmpty() || fields.isNotEmpty() || fieldStarted)) endRecord()
        return records
    }
}
