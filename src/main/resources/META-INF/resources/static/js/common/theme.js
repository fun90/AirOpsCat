(() => {
    const colorScheme = window.matchMedia('(prefers-color-scheme: dark)');
    const applyTheme = () => {
        document.documentElement.setAttribute('data-bs-theme', colorScheme.matches ? 'dark' : 'light');
    };

    // 在样式和正文加载前应用主题，避免暗色模式下闪现亮色页面。
    applyTheme();
    colorScheme.addEventListener('change', applyTheme);
})();
