package com.expenselens.export

import android.content.Context
import com.expenselens.data.storage.BillStorage
import com.expenselens.domain.model.Expense
import com.expenselens.ui.common.Format
import com.opencsv.CSVWriter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.time.format.DateTimeFormatter
import java.util.Locale

class ExportService {

    suspend fun exportCsv(context: Context, expenses: List<Expense>): File =
        withContext(Dispatchers.IO) {
            val file = File(BillStorage.exportsDir(context), "expenses-${stamp()}.csv")
            CSVWriter(FileWriter(file)).use { csv ->
                csv.writeNext(
                    arrayOf(
                        "Date", "Vendor", "BillNumber", "Category",
                        "Quantity", "UnitPrice", "LineTotal", "LineDescription",
                        "TotalAmount", "TaxAmount", "Currency", "PaymentMethod", "Notes"
                    )
                )
                expenses.forEach { e ->
                    if (e.lineItems.isEmpty()) {
                        csv.writeNext(
                            arrayOf(
                                e.billDate.toString(), e.vendor, e.billNumber.orEmpty(),
                                "", "", "", "", "",
                                e.totalAmount.toString(),
                                e.taxAmount?.toString() ?: "",
                                e.currency, e.paymentMethod.displayName, e.notes.orEmpty()
                            )
                        )
                    } else {
                        e.lineItems.forEach { li ->
                            csv.writeNext(
                                arrayOf(
                                    e.billDate.toString(), e.vendor, e.billNumber.orEmpty(),
                                    li.category.displayName,
                                    li.quantity.toString(),
                                    li.unitPrice.toString(),
                                    li.lineTotal.toString(),
                                    li.description,
                                    e.totalAmount.toString(),
                                    e.taxAmount?.toString() ?: "",
                                    e.currency, e.paymentMethod.displayName, e.notes.orEmpty()
                                )
                            )
                        }
                    }
                }
            }
            file
        }

    suspend fun exportXlsx(context: Context, expenses: List<Expense>): File =
        withContext(Dispatchers.IO) {
            val file = File(BillStorage.exportsDir(context), "expenses-${stamp()}.xlsx")
            val wb = XSSFWorkbook()
            val summary = wb.createSheet("Summary")
            val summaryHeader = summary.createRow(0)
            val cols = arrayOf(
                "Date", "Vendor", "BillNumber", "TotalAmount",
                "TaxAmount", "Currency", "PaymentMethod", "Notes", "Category"
            )
            cols.forEachIndexed { i, c -> summaryHeader.createCell(i).setCellValue(c) }
            expenses.forEachIndexed { idx, e ->
                val row = summary.createRow(idx + 1)
                row.createCell(0).setCellValue(e.billDate.toString())
                row.createCell(1).setCellValue(e.vendor)
                row.createCell(2).setCellValue(e.billNumber ?: "")
                row.createCell(3).setCellValue(e.totalAmount)
                row.createCell(4).setCellValue(e.taxAmount ?: 0.0)
                row.createCell(5).setCellValue(e.currency)
                row.createCell(6).setCellValue(e.paymentMethod.displayName)
                row.createCell(7).setCellValue(e.notes ?: "")
                row.createCell(8).setCellValue(
                    e.lineItems.firstOrNull()?.category?.displayName ?: ""
                )
            }

            val items = wb.createSheet("Line items")
            val itemHeader = items.createRow(0)
            arrayOf("Date", "Vendor", "Description", "Quantity", "UnitPrice", "LineTotal", "Category")
                .forEachIndexed { i, c -> itemHeader.createCell(i).setCellValue(c) }
            var rowIdx = 1
            expenses.forEach { e ->
                e.lineItems.forEach { li ->
                    val r = items.createRow(rowIdx++)
                    r.createCell(0).setCellValue(e.billDate.toString())
                    r.createCell(1).setCellValue(e.vendor)
                    r.createCell(2).setCellValue(li.description)
                    r.createCell(3).setCellValue(li.quantity)
                    r.createCell(4).setCellValue(li.unitPrice)
                    r.createCell(5).setCellValue(li.lineTotal)
                    r.createCell(6).setCellValue(li.category.displayName)
                }
            }
            FileOutputStream(file).use { wb.write(it) }
            wb.close()
            file
        }

    suspend fun exportPdf(context: Context, expenses: List<Expense>): File =
        withContext(Dispatchers.IO) {
            val file = File(BillStorage.exportsDir(context), "expenses-${stamp()}.pdf")
            val doc = PDDocument()
            val margin = 40f
            val lineHeight = 14f
            val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

            // Materialize the report as a list of (text, bold) lines first, then
            // walk through them and create pages lazily. This avoids the
            // trap of juggling multiple PDPageContentStream instances.
            val lines = mutableListOf<Pair<String, Boolean>>()
            lines += "ExpenseLens report" to true
            lines += "Generated: ${java.time.LocalDateTime.now().format(dateFmt)}" to false
            lines += "" to false
            expenses.forEach { e ->
                lines += "${Format.date(e.billDate)} — ${e.vendor} — ${Format.money(e.totalAmount, e.currency)}" to true
                e.lineItems.forEach { li ->
                    lines += "  • ${li.description} (${li.category.displayName}) — ${Format.money(li.lineTotal, e.currency)}" to false
                }
                e.notes?.takeIf { it.isNotBlank() }?.let { lines += "  Notes: $it" to false }
                lines += "" to false
            }

            var page = PDPage()
            doc.addPage(page)
            var stream = PDPageContentStream(doc, page)
            var y = page.mediaBox.height - margin

            fun startNewPage() {
                stream.close()
                page = PDPage()
                doc.addPage(page)
                stream = PDPageContentStream(doc, page)
                y = page.mediaBox.height - margin
            }

            fun draw(text: String, bold: Boolean) {
                if (y < margin + lineHeight) startNewPage()
                stream.beginText()
                stream.setFont(if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, 10f)
                stream.newLineAtOffset(margin, y)
                stream.showText(text.take(120))
                stream.endText()
                y -= lineHeight
            }

            lines.forEach { (text, bold) -> draw(text, bold) }
            stream.close()
            doc.save(file)
            doc.close()
            file
        }

    private fun stamp(): String =
        java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
}
