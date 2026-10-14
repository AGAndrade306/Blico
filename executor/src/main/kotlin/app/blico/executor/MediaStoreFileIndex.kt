package app.blico.executor

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import app.blico.core.FileIndex
import app.blico.core.FileItem
import app.blico.core.FileKind
import app.blico.core.FileQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Busca nos arquivos do aparelho pelo MediaStore. Precisa do "acesso a todos os arquivos"
 * para enxergar PDFs e documentos, não só fotos e vídeos. Nada daqui sai do aparelho.
 */
class MediaStoreFileIndex(context: Context) : FileIndex {
    private val resolver = context.applicationContext.contentResolver

    override fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    override suspend fun search(query: FileQuery): List<FileItem> = withContext(Dispatchers.IO) {
        val where = mutableListOf("${MediaStore.MediaColumns.SIZE} > 0", "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL")
        val args = mutableListOf<String>()
        // Acentos viram curinga: "relatório" acha "relatorio" e "Relatório". O filtro fino é o FileSearch.rank.
        query.terms.forEach { term ->
            where += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            args += "%${likePattern(term)}%"
        }
        query.modifiedAfterMs?.let {
            where += "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
            args += (it / 1000).toString()
        }
        query.kind?.let { kind -> kindFilter(kind)?.let { (clause, values) -> where += clause; args += values } }
        // Arquivos de apps e da lixeira ficam de fora.
        where += "(${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE 'Android/%')"

        val queryArgs = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, where.joinToString(" AND "))
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, (query.limit * 10).coerceAtMost(500))
        }
        read(resolver.query(FILES, PROJECTION, queryArgs, null))
    }

    override suspend fun get(id: String): FileItem? = withContext(Dispatchers.IO) {
        val n = id.toLongOrNull() ?: return@withContext null
        read(resolver.query(ContentUris.withAppendedId(FILES, n), PROJECTION, null, null)).firstOrNull()
    }

    private fun read(cursor: android.database.Cursor?): List<FileItem> = cursor?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        buildList {
            while (c.moveToNext()) {
                val display = c.getString(name) ?: continue
                add(
                    FileItem(
                        id = c.getLong(id).toString(),
                        name = display,
                        mime = c.getString(mime).orEmpty(),
                        sizeBytes = c.getLong(size),
                        modifiedMs = c.getLong(modified) * 1000,
                        folder = c.getString(path).orEmpty(),
                    ),
                )
            }
        }
    }.orEmpty()

    private fun kindFilter(kind: FileKind): Pair<String, List<String>>? {
        if (kind == FileKind.OUTRO) return null
        val clauses = kind.mimes.map { "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?" } +
            kind.extensions.map { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" }
        val values = kind.mimes.map { "$it%" } + kind.extensions.map { "%.$it" }
        return "(" + clauses.joinToString(" OR ") + ")" to values
    }

    companion object {
        private val FILES: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )

        /** URI de conteúdo para abrir ou compartilhar. Mídia usa a coleção própria, que os visualizadores entendem melhor. */
        fun uriFor(id: String, mime: String): Uri {
            val base = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else -> FILES
            }
            return ContentUris.withAppendedId(base, id.toLong())
        }

        /** Caracteres fora do ASCII e os curingas do LIKE viram "_" (qualquer caractere). */
        internal fun likePattern(term: String): String =
            Normalizer.normalize(term, Normalizer.Form.NFC).map { ch ->
                if (ch.code > 127 || ch == '%' || ch == '_') '_' else ch
            }.joinToString("")
    }
}
