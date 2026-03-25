/**
 * Common utility functions for the application
 */

/**
 * 格式化日期时间为本地时间（北京时间 UTC+8）
 * @param {Date} date - 要格式化的日期对象
 * @returns {string} 格式化后的日期时间字符串 (YYYY-MM-DDTHH:MM)
 */
export function formatDateTimeForLocal(date) {
    if (!date) return '';
    
    // 创建北京时间的Date对象（UTC+8）
    const beijingOffset = 8 * 60; // 北京时间偏移分钟数
    const utc = date.getTime() + (date.getTimezoneOffset() * 60000);
    const beijingTime = new Date(utc + (beijingOffset * 60000));
    
    // 格式化为 YYYY-MM-DDTHH:MM 格式
    const year = beijingTime.getFullYear();
    const month = String(beijingTime.getMonth() + 1).padStart(2, '0');
    const day = String(beijingTime.getDate()).padStart(2, '0');
    const hours = String(beijingTime.getHours()).padStart(2, '0');
    const minutes = String(beijingTime.getMinutes()).padStart(2, '0');
    
    return `${year}-${month}-${day}T${hours}:${minutes}`;
}

/**
 * 格式化日期时间为显示格式（北京时间）
 * @param {string|Date} dateTime - 要格式化的日期时间
 * @returns {string} 格式化后的日期时间字符串 (YYYY-MM-DD HH:MM:SS)
 */
export function formatDateTimeForDisplay(dateTime) {
    if (!dateTime) return '';
    
    const date = new Date(dateTime);
    if (isNaN(date.getTime())) return '';
    
    // 创建北京时间的Date对象（UTC+8）
    const beijingOffset = 8 * 60; // 北京时间偏移分钟数
    const utc = date.getTime() + (date.getTimezoneOffset() * 60000);
    const beijingTime = new Date(utc + (beijingOffset * 60000));
    
    const year = beijingTime.getFullYear();
    const month = String(beijingTime.getMonth() + 1).padStart(2, '0');
    const day = String(beijingTime.getDate()).padStart(2, '0');
    const hours = String(beijingTime.getHours()).padStart(2, '0');
    const minutes = String(beijingTime.getMinutes()).padStart(2, '0');
    const seconds = String(beijingTime.getSeconds()).padStart(2, '0');
    
    return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`;
}

/**
 * 格式化日期时间为完整显示格式（斜杠分隔日期，含秒）
 * @param {string|Date} dateTime - 要格式化的日期时间
 * @returns {string} 格式化后的日期时间字符串 (YYYY/MM/DD HH:MM:SS)
 */
export function formatDateTimeFull(dateTime) {
    if (!dateTime) return '';

    const date = new Date(dateTime);
    if (isNaN(date.getTime())) return '';

    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    const seconds = String(date.getSeconds()).padStart(2, '0');

    return `${year}/${month}/${day} ${hours}:${minutes}:${seconds}`;
}

/**
 * 格式化相对时间（多少分钟前、小时前等）
 * @param {string|Date} dateTime - 要格式化的日期时间
 * @returns {string} 相对时间字符串
 */
export function formatRelativeTime(dateTime) {
    if (!dateTime) return '';
    
    const date = new Date(dateTime);
    if (isNaN(date.getTime())) return '';
    
    const now = new Date();
    const diffInMs = now.getTime() - date.getTime();
    const diffInMinutes = Math.floor(diffInMs / (1000 * 60));
    
    if (diffInMinutes < 1) {
        return '刚刚';
    } else if (diffInMinutes < 60) {
        return `${diffInMinutes}分钟前`;
    } else if (diffInMinutes < 1440) { // 24 hours
        const hours = Math.floor(diffInMinutes / 60);
        const remainingMinutes = diffInMinutes % 60;
        if (remainingMinutes > 0) {
            return `${hours}小时${remainingMinutes}分钟前`;
        } else {
            return `${hours}小时前`;
        }
    } else if (diffInMinutes < 10080) { // 7 days
        const days = Math.floor(diffInMinutes / 1440);
        return `${days}天前`;
    } else {
        // 超过7天显示具体日期
        return formatDateTimeForDisplay(dateTime);
    }
}