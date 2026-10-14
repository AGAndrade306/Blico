package app.blico.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import app.blico.ui.Display
import app.blico.ui.LocalRiso
import app.blico.ui.RisoButton
import app.blico.ui.RisoLink
import app.blico.ui.grain
import app.blico.core.FileItem
import app.blico.core.FileKind
import app.blico.core.ToolCall
import app.blico.core.formatSize
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lista de arquivos encontrados. Tocar num item mostra as ações; cada ação vira uma chamada
 * de ferramenta e passa pela mesma política de risco dos comandos de voz.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileResults(items: List<FileItem>, onAction: (ToolCall) -> Unit) {
    var selected by remember(items) { mutableStateOf(items.singleOrNull()?.id) }
    var renaming by remember(items) { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(items, key = { it.id }) { file ->
            val args = mapOf("id" to file.id, "nome" to file.name)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        selected = if (selected == file.id) null else file.id
                        renaming = null
                    }
                    .padding(vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(file.kind)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(file.kind.label, formatSize(file.sizeBytes), whenText(file.modifiedMs), folderText(file.folder))
                                .filter { it.isNotEmpty() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalRiso.current.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (selected == file.id) {
                    Spacer(Modifier.size(8.dp))
                    if (renaming == file.id) {
                        RenameField(file) { newName ->
                            onAction(ToolCall("arquivo_renomear", args + ("novo_nome" to newName)))
                        }
                        RisoLink("Cancelar", onClick = { renaming = null })
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RisoButton("Abrir", onClick = { onAction(ToolCall("arquivo_abrir", args)) })
                            RisoButton("Compartilhar", onClick = { onAction(ToolCall("arquivo_compartilhar", args)) }, primary = false)
                            RisoButton("Renomear", onClick = { renaming = file.id }, primary = false)
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.5.dp, color = LocalRiso.current.green.copy(alpha = 0.25f))
        }
    }
}

@Composable
private fun RenameField(file: FileItem, onSubmit: (String) -> Unit) {
    // Edita só o nome; a extensão fica, a não ser que a pessoa digite outra.
    var name by remember(file.id) { mutableStateOf(file.name.substringBeforeLast('.')) }
    val submit = { if (name.isNotBlank()) onSubmit(name.trim()) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Novo nome") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.size(8.dp))
    RisoButton("Renomear", onClick = submit, enabled = name.isNotBlank())
}

/** Selo do tipo, impresso em rosa: mais legível que um emoji e no mesmo estilo do resto. */
@Composable
private fun KindBadge(kind: FileKind) {
    val p = LocalRiso.current
    val label = when (kind) {
        FileKind.IMAGEM -> "IMG"
        FileKind.VIDEO -> "VÍD"
        FileKind.AUDIO -> "ÁUD"
        FileKind.PDF -> "PDF"
        FileKind.DOCUMENTO -> "DOC"
        FileKind.PLANILHA -> "XLS"
        FileKind.APRESENTACAO -> "PPT"
        FileKind.OUTRO -> "ARQ"
    }
    Box(
        Modifier
            .size(width = 46.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(p.pink)
            .grain(),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontFamily = Display), color = p.green)
    }
}

private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

/** "hoje 14:20", "ontem 09:05", "12 de out.", "3 de mar. de 2024". */
private fun whenText(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    val hour = time.format(DateTimeFormatter.ofPattern("HH:mm", PT_BR))
    return when (time.toLocalDate()) {
        today -> "hoje $hour"
        today.minusDays(1) -> "ontem $hour"
        else -> time.format(
            DateTimeFormatter.ofPattern(if (time.year == today.year) "d 'de' MMM" else "d 'de' MMM 'de' yyyy", PT_BR),
        )
    }
}

/** "Download/" → "Download"; a pasta da câmera ganha um nome mais claro. */
private fun folderText(folder: String): String {
    val clean = folder.trimEnd('/')
    return when {
        clean.startsWith("DCIM/Camera") -> "Câmera"
        clean.startsWith("Pictures/Screenshots") || clean.startsWith("DCIM/Screenshots") -> "Capturas de tela"
        else -> clean.substringAfterLast('/')
    }
}
