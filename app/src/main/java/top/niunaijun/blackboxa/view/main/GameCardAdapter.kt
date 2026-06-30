package top.niunaijun.blackboxa.view.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.data.network.model.GameInfo

/**
 * GameCardAdapter — Renders game cards in the dashboard grid.
 * Each card shows: game icon, title, architecture badge, and install status.
 */
class GameCardAdapter(
    private val onGameClick: (GameInfo) -> Unit
) : ListAdapter<GameInfo, GameCardAdapter.GameCardViewHolder>(GameDiffCallback()) {

    // Track install status per game_id
    private val installStatus = mutableMapOf<String, InstallStatus>()

    enum class InstallStatus {
        NOT_INSTALLED,
        DOWNLOADING,
        INSTALLING,
        INSTALLED,
        FAILED
    }

    fun setInstallStatus(gameId: String, status: InstallStatus) {
        installStatus[gameId] = status
        currentList.forEachIndexed { index, game ->
            if (game.gameId == gameId) {
                notifyItemChanged(index)
            }
        }
    }

    fun getInstallStatus(gameId: String): InstallStatus {
        return installStatus[gameId] ?: InstallStatus.NOT_INSTALLED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameCardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game_card, parent, false)
        return GameCardViewHolder(view)
    }

    override fun onBindViewHolder(holder: GameCardViewHolder, position: Int) {
        val game = getItem(position)
        holder.bind(game, installStatus[game.gameId] ?: InstallStatus.NOT_INSTALLED)
    }

    inner class GameCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivGameIcon: ImageView = itemView.findViewById(R.id.ivGameIcon)
        private val tvGameTitle: TextView = itemView.findViewById(R.id.tvGameTitle)
        private val tvArchBadge: TextView = itemView.findViewById(R.id.tvArchBadge)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)

        fun bind(game: GameInfo, status: InstallStatus) {
            tvGameTitle.text = game.title

            // Architecture badge
            val arch = game.architectureType
            if (arch.contains("32", ignoreCase = true)) {
                tvArchBadge.text = "32-bit"
                tvArchBadge.visibility = View.VISIBLE
            } else if (arch.contains("64", ignoreCase = true)) {
                tvArchBadge.text = "64-bit"
                tvArchBadge.visibility = View.VISIBLE
            } else {
                tvArchBadge.visibility = View.GONE
            }

            // Install status text
            when (status) {
                InstallStatus.NOT_INSTALLED -> {
                    tvStatus.text = itemView.context.getString(R.string.status_tap_to_install)
                    tvStatus.setTextColor(itemView.context.getColor(R.color.secondary_text))
                    tvStatus.visibility = View.VISIBLE
                }
                InstallStatus.DOWNLOADING -> {
                    tvStatus.text = itemView.context.getString(R.string.status_downloading)
                    tvStatus.setTextColor(itemView.context.getColor(R.color.primary))
                    tvStatus.visibility = View.VISIBLE
                }
                InstallStatus.INSTALLING -> {
                    tvStatus.text = itemView.context.getString(R.string.status_installing)
                    tvStatus.setTextColor(itemView.context.getColor(R.color.primary))
                    tvStatus.visibility = View.VISIBLE
                }
                InstallStatus.INSTALLED -> {
                    tvStatus.text = itemView.context.getString(R.string.status_installed)
                    tvStatus.setTextColor(itemView.context.getColor(R.color.status_installed))
                    tvStatus.visibility = View.VISIBLE
                }
                InstallStatus.FAILED -> {
                    tvStatus.text = itemView.context.getString(R.string.status_failed)
                    tvStatus.setTextColor(itemView.context.getColor(R.color.status_error))
                    tvStatus.visibility = View.VISIBLE
                }
            }

            // Load game icon — use a deterministic placeholder based on game title
            loadGameIcon(game)

            // Click handler
            itemView.setOnClickListener {
                onGameClick(game)
            }
        }

        private fun loadGameIcon(game: GameInfo) {
            // Generate a deterministic color from the game title for the placeholder
            val colors = intArrayOf(
                0xFFE53935.toInt(), // Red
                0xFF1E88E5.toInt(), // Blue
                0xFF43A047.toInt(), // Green
                0xFFFB8C00.toInt(), // Orange
                0xFF8E24AA.toInt(), // Purple
                0xFF00ACC1.toInt(), // Cyan
                0xFFD81B60.toInt(), // Pink
                0xFF3949AB.toInt(), // Indigo
                0xFF00897B.toInt()  // Teal
            )
            val colorIndex = game.title.hashCode().and(0x7FFFFFFF) % colors.size
            val color = colors[colorIndex]

            // Create a simple colored circle bitmap as placeholder
            val size = 128
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply {
                this.color = color
                isAntiAlias = true
            }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

            // Draw first letter of game title
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 56f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.isFakeBoldText = true
            val firstLetter = game.title.first().uppercaseChar().toString()
            val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(firstLetter, size / 2f, textY, paint)

            ivGameIcon.setImageBitmap(bitmap)
        }
    }

    class GameDiffCallback : DiffUtil.ItemCallback<GameInfo>() {
        override fun areItemsTheSame(oldItem: GameInfo, newItem: GameInfo): Boolean {
            return oldItem.gameId == newItem.gameId
        }

        override fun areContentsTheSame(oldItem: GameInfo, newItem: GameInfo): Boolean {
            return oldItem == newItem
        }
    }
}
