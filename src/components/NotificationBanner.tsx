import React, { useEffect } from 'react';
import { Bell, Sparkles, X } from 'lucide-react';

interface NotificationBannerProps {
  notification: { title: string; body: string } | null;
  onDismiss: () => void;
  onOpenBriefing: () => void;
}

export const NotificationBanner: React.FC<NotificationBannerProps> = ({
  notification,
  onDismiss,
  onOpenBriefing,
}) => {
  useEffect(() => {
    if (notification) {
      const timer = setTimeout(() => {
        onDismiss();
      }, 7000);
      return () => clearTimeout(timer);
    }
  }, [notification, onDismiss]);

  if (!notification) return null;

  return (
    <div className="absolute top-10 left-3 right-3 z-50 animate-bounce-short">
      <div className="bg-slate-900/95 border border-indigo-500/40 backdrop-blur-md rounded-2xl p-3 shadow-2xl flex items-start space-x-3 text-slate-100">
        <div className="w-8 h-8 rounded-xl bg-indigo-600 flex items-center justify-center flex-shrink-0 mt-0.5 shadow-md shadow-indigo-600/30">
          <Bell className="w-4 h-4 text-white" />
        </div>

        <div className="flex-1 min-w-0" onClick={onOpenBriefing}>
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-bold text-indigo-300 truncate">{notification.title}</h4>
            <span className="text-[10px] text-slate-400">now</span>
          </div>
          <p className="text-xs text-slate-200 mt-0.5 line-clamp-2 leading-tight">
            {notification.body}
          </p>
        </div>

        <button
          onClick={onDismiss}
          className="p-1 hover:bg-slate-800 text-slate-400 hover:text-slate-200 rounded-lg transition cursor-pointer"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
};
