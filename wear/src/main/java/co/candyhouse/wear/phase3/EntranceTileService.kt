package co.candyhouse.wear.phase3

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders
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
        val visual = resolveWearVisualState(display.deviceAState, display.deviceBState)
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

        val root = LayoutElementBuilders.Column.Builder()
            .addContent(LayoutElementBuilders.Text.Builder().setText("玄関").build())
            .addContent(LayoutElementBuilders.Text.Builder().setText(visual.groupLabel()).build())
            .addContent(buildVisualContent(visual))
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
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(LOCKED_IMAGE_ID, androidImageResource(R.drawable.phase3_lock_locked))
                .addIdToImageMapping(UNLOCKED_IMAGE_ID, androidImageResource(R.drawable.phase3_lock_unlocked))
                .build()
        )

    private fun buildVisualContent(visual: WearVisualState): LayoutElementBuilders.LayoutElement = when (visual) {
        is WearVisualState.Single -> tileImage(visual.state, LARGE_IMAGE_DP)
        is WearVisualState.Split -> LayoutElementBuilders.Row.Builder()
            .addContent(tileSplitColumn("上", visual.top))
            .addContent(tileSplitColumn("下", visual.bottom))
            .build()
        WearVisualState.Unknown -> LayoutElementBuilders.Text.Builder().setText("? 不明").build()
    }

    private fun tileSplitColumn(label: String, state: WearVisualLockState): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .addContent(LayoutElementBuilders.Text.Builder().setText(label).build())
        if (state == WearVisualLockState.UNKNOWN) {
            column.addContent(LayoutElementBuilders.Text.Builder().setText("?").build())
        } else {
            column.addContent(tileImage(state, SMALL_IMAGE_DP))
        }
        return column
            .addContent(LayoutElementBuilders.Text.Builder().setText(state.label()).build())
            .build()
    }

    private fun tileImage(state: WearVisualLockState, sizeDp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Image.Builder()
            .setResourceId(
                when (state) {
                    WearVisualLockState.LOCKED -> LOCKED_IMAGE_ID
                    WearVisualLockState.UNLOCKED -> UNLOCKED_IMAGE_ID
                    WearVisualLockState.UNKNOWN -> error("UNKNOWN has no definite lock image")
                }
            )
            .setWidth(DimensionBuilders.dp(sizeDp))
            .setHeight(DimensionBuilders.dp(sizeDp))
            .build()

    private fun androidImageResource(drawableRes: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder()
                    .setResourceId(drawableRes)
                    .build()
            )
            .build()

    private fun WearVisualLockState.label(): String = when (this) {
        WearVisualLockState.LOCKED -> "施錠"
        WearVisualLockState.UNLOCKED -> "解錠"
        WearVisualLockState.UNKNOWN -> "不明"
    }

    companion object {
        private const val RESOURCES_VERSION = "2"
        private const val LOCKED_IMAGE_ID = "phase3_locked"
        private const val UNLOCKED_IMAGE_ID = "phase3_unlocked"
        private const val LARGE_IMAGE_DP = 96f
        private const val SMALL_IMAGE_DP = 48f
    }
}
