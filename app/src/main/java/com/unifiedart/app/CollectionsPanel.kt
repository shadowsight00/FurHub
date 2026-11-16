package com.unifiedart.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unifiedart.app.data.*
import org.json.JSONObject

@Composable
fun CollectionsPanel(model: GalleryViewModel, openGallery: () -> Unit) {
    val records by model.records.collectAsState()
    val artists by model.local.artists.collectAsState()
    var name by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Your collections", style = MaterialTheme.typography.titleLarge)
            Row {
                OutlinedTextField(name, { name = it }, label = { Text("New collection") }, modifier = Modifier.weight(1f), singleLine = true)
                TextButton(onClick = { model.createCollection(name.trim()); name = "" }, enabled = name.isNotBlank()) { Text("Create") }
            }
            TextButton(onClick = { model.openCollection("bookmark"); openGallery() }) { Text("Local bookmarks (${records.count { it.kind == "bookmark" }})") }
        }
        items(records.filter { it.kind == "collection" }, key = { it.key }) { record ->
            Row {
                TextButton(onClick = { model.openCollection("item", record.label); openGallery() }, modifier = Modifier.weight(1f)) { Text("${record.label} (${records.count { it.kind == "item" && it.groupName == record.label }})") }
                TextButton(onClick = { model.removeRecord(record.key) }) { Text("Remove") }
            }
        }
        item { HorizontalDivider(); Text("Saved paired searches", style = MaterialTheme.typography.titleMedium) }
        items(records.filter { it.kind == "search" }, key = { it.key }) { record ->
            Row {
                TextButton(onClick = {
                    val query = JSONObject(record.payload)
                    model.search(SearchQuery(query.optString("e621"), query.optString("inkbunny"))); openGallery()
                }, modifier = Modifier.weight(1f)) { Text(record.label) }
                TextButton(onClick = { model.removeRecord(record.key) }) { Text("Remove") }
            }
        }
        item { HorizontalDivider(); Text("Followed e621 artists", style = MaterialTheme.typography.titleMedium) }
        items(artists.sorted(), key = { "artist:$it" }) { artist ->
            Row {
                TextButton(onClick = { model.search(SearchQuery(e621 = artist, enabled = setOf(Source.E621))); openGallery() }, modifier = Modifier.weight(1f)) { Text(artist) }
                TextButton(onClick = { model.toggleArtist(artist) }) { Text("Unfollow") }
            }
        }
        item { HorizontalDivider(); Text("Favorite artists", style = MaterialTheme.typography.titleMedium) }
        items(records.filter { it.kind == "artist" }, key = { it.key }) { record ->
            Row {
                TextButton(onClick = {
                    val source = Source.valueOf(record.payload)
                    model.search(if (source == Source.E621) SearchQuery(e621 = record.label, enabled = setOf(source)) else SearchQuery(inkArtist = record.label, enabled = setOf(source)))
                    openGallery()
                }, modifier = Modifier.weight(1f)) { Text("${record.label} · ${record.payload}") }
                TextButton(onClick = { model.removeRecord(record.key) }) { Text("Remove") }
            }
        }
        item {
            HorizontalDivider(); Text("Inkbunny watched artists", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { model.syncWatchedArtists() }) { Text("Refresh watchlist") }
        }
        items(records.filter { it.kind == "watch" && it.groupName == model.accounts.inkId }, key = { it.key }) { record ->
            TextButton(onClick = { model.search(SearchQuery(inkArtist = record.label, enabled = setOf(Source.INKBUNNY))); openGallery() }) { Text(record.label) }
        }
        item { HorizontalDivider(); Text("Saved e621 pools", style = MaterialTheme.typography.titleMedium) }
        items(records.filter { it.kind == "pool" }, key = { it.key }) { record ->
            Row {
                TextButton(onClick = { model.search(SearchQuery(e621 = "pool:${record.payload}", enabled = setOf(Source.E621))); openGallery() }, modifier = Modifier.weight(1f)) { Text(record.label) }
                TextButton(onClick = { model.removeRecord(record.key) }) { Text("Remove") }
            }
        }
        item { Text("Bookmarks and collections stay on this device and don't change site favorites.", style = MaterialTheme.typography.bodySmall) }
    }
}
