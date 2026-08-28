package co.candyhouse.wear.phase3

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class EntranceTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val display = WearStateStore(this).load()
        val launchActivity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(WearMainActivity::class.java.name)
            .addKeyToExtraMapping(
                WearMainActivity.EXTRA_AUTO_ACTIVATE,
                ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build(),
            )
            .build()
        val launch = ActionBuilders.LaunchAction.Builder().setAndroidActivity(launchActivity).build()
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("entrance_one_button")
            .setOnClick(launch)
            .build()
        val actionModifiers = ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build()

        val stateText = when (display.groupState) {
            "LOCKED" -> "施錠"
            "UNLOCKED" -> "解錠"
            "MIXED" -> "混在"
            else -> "不明"
        }
        val root = LayoutElementBuilders.Column.Builder()
            .addContent(LayoutElementBuilders.Text.Builder().setText("玄関").build())
            .addContent(LayoutElementBuilders.Text.Builder().setText(stateText).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("操作")
                    .setModifiers(actionModifiers)
                    .build()
            )
            .build()
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(root).build()
        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build())
            .build()
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(timeline)
                .build()
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}
