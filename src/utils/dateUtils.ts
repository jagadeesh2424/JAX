export function formatRelativeTime(isoString: string | null): { text: string; isOverdue: boolean; isToday: boolean } {
  if (!isoString) {
    return { text: 'No deadline', isOverdue: false, isToday: false };
  }

  const date = new Date(isoString);
  if (isNaN(date.getTime())) {
    return { text: 'Invalid date', isOverdue: false, isToday: false };
  }

  const now = new Date();
  const diffMs = date.getTime() - now.getTime();
  const diffMinutes = Math.round(diffMs / (1000 * 60));
  const diffHours = Math.round(diffMs / (1000 * 60 * 60));
  const diffDays = Math.round(diffMs / (1000 * 60 * 60 * 24));

  const isOverdue = diffMs < 0;
  
  const isToday =
    date.getDate() === now.getDate() &&
    date.getMonth() === now.getMonth() &&
    date.getFullYear() === now.getFullYear();

  const formattedTime = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  if (isToday) {
    if (isOverdue) {
      return { text: `Overdue today at ${formattedTime}`, isOverdue: true, isToday: true };
    }
    return { text: `Today at ${formattedTime}`, isOverdue: false, isToday: true };
  }

  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  const isTomorrow =
    date.getDate() === tomorrow.getDate() &&
    date.getMonth() === tomorrow.getMonth() &&
    date.getFullYear() === tomorrow.getFullYear();

  if (isTomorrow) {
    return { text: `Tomorrow at ${formattedTime}`, isOverdue: false, isToday: false };
  }

  if (diffDays < 0) {
    const absDays = Math.abs(diffDays);
    return {
      text: `Overdue by ${absDays} ${absDays === 1 ? 'day' : 'days'} (${date.toLocaleDateString([], { month: 'short', day: 'numeric' })})`,
      isOverdue: true,
      isToday: false,
    };
  }

  if (diffDays <= 7) {
    const dayName = date.toLocaleDateString([], { weekday: 'short' });
    return { text: `${dayName} at ${formattedTime}`, isOverdue: false, isToday: false };
  }

  return {
    text: date.toLocaleDateString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }),
    isOverdue: false,
    isToday: false,
  };
}

export function formatFullDateTime(isoString: string | null): string {
  if (!isoString) return 'No deadline set';
  const date = new Date(isoString);
  if (isNaN(date.getTime())) return 'Invalid date';
  return date.toLocaleString([], {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}
