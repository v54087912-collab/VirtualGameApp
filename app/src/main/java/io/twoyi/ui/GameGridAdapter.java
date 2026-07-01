package io.twoyi.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

import io.twoyi.R;
import io.twoyi.model.GameItem;

public class GameGridAdapter extends RecyclerView.Adapter<GameGridAdapter.GameViewHolder> {
    
    public interface OnGameClickListener {
        void onGameClick(GameItem game);
        void onGameLongClick(GameItem game);
    }
    
    private final List<GameItem> games = new ArrayList<>();
    private OnGameClickListener listener;
    
    public void setOnGameClickListener(OnGameClickListener listener) {
        this.listener = listener;
    }
    
    public void setGames(List<GameItem> newGames) {
        games.clear();
        if (newGames != null) {
            games.addAll(newGames);
        }
        notifyDataSetChanged();
    }
    
    public void addGame(GameItem game) {
        games.add(game);
        notifyItemInserted(games.size() - 1);
    }
    
    public void clear() {
        games.clear();
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public GameViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_game_grid, parent, false);
        return new GameViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull GameViewHolder holder, int position) {
        GameItem game = games.get(position);
        holder.bind(game);
    }
    
    @Override
    public int getItemCount() {
        return games.size();
    }
    
    class GameViewHolder extends RecyclerView.ViewHolder {
        private final ImageView iconView;
        private final TextView titleView;
        private final TextView controlTypeBadge;
        
        GameViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.game_icon);
            titleView = itemView.findViewById(R.id.game_title);
            controlTypeBadge = itemView.findViewById(R.id.control_type_badge);
        }
        
        void bind(GameItem game) {
            titleView.setText(game.getTitle());
            
            String controlType = game.getControl_type();
            if (controlType != null && !controlType.isEmpty()) {
                controlTypeBadge.setText(controlType.toUpperCase());
                controlTypeBadge.setVisibility(View.VISIBLE);
            } else {
                controlTypeBadge.setVisibility(View.GONE);
            }
            
            String iconUrl = game.getIcon_url();
            if (iconUrl != null && !iconUrl.isEmpty()) {
                Glide.with(itemView.getContext())
                    .load(iconUrl)
                    .placeholder(R.drawable.ic_game_placeholder)
                    .error(R.drawable.ic_game_placeholder)
                    .centerCrop()
                    .into(iconView);
            } else {
                iconView.setImageResource(R.drawable.ic_game_placeholder);
            }
            
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onGameClick(game);
                }
            });
            
            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onGameLongClick(game);
                }
                return true;
            });
        }
    }
}
