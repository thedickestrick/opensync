package com.opensync.foldersync.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class FormFieldType { TEXT, CHECKBOX, CHOICE, OTHER }

/** One editable AcroForm field, addressed by its [fullName]. */
data class FormField(
    val fullName: String,
    val label: String,
    val type: FormFieldType,
    val value: String,
    val options: List<String> = emptyList()
)

/** Reads and writes interactive PDF form (AcroForm) fields via PDFBox. */
object PdfForm {

    suspend fun readFields(src: File): List<FormField> = withContext(Dispatchers.IO) {
        PDDocument.load(src).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return@use emptyList()
            val out = ArrayList<FormField>()
            val iter = form.fieldIterator
            while (iter.hasNext()) {
                val f = iter.next()
                if (f is PDNonTerminalField || f is PDPushButton) continue
                val name = f.fullyQualifiedName ?: continue
                val label = f.partialName?.takeIf { it.isNotBlank() } ?: name
                val field = when (f) {
                    is PDTextField ->
                        FormField(name, label, FormFieldType.TEXT, f.valueAsString ?: "")
                    is PDCheckBox ->
                        FormField(name, label, FormFieldType.CHECKBOX, if (f.isChecked) "true" else "false")
                    is PDChoice ->
                        FormField(
                            name, label, FormFieldType.CHOICE, f.valueAsString ?: "",
                            options = runCatching { f.options }.getOrDefault(emptyList())
                        )
                    else ->
                        FormField(name, label, FormFieldType.OTHER, f.valueAsString ?: "")
                }
                out.add(field)
            }
            out
        }
    }

    suspend fun save(src: File, dest: File, values: Map<String, String>) = withContext(Dispatchers.IO) {
        PDDocument.load(src).use { doc ->
            val form = doc.documentCatalog.acroForm
                ?: throw IllegalStateException("This PDF has no form fields")
            for ((name, value) in values) {
                val field = form.getField(name) ?: continue
                runCatching {
                    when (field) {
                        is PDTextField -> field.setValue(value)
                        is PDCheckBox -> if (value == "true") field.check() else field.unCheck()
                        is PDChoice -> field.setValue(value)
                        else -> Unit // radio / other: left unchanged
                    }
                }
            }
            doc.save(dest)
        }
    }
}
