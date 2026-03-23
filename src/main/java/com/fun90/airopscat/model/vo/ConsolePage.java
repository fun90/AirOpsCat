package com.fun90.airopscat.model.vo;

public record ConsolePage(
        String moduleKey,
        String moduleTitle,
        int moduleOrder,
        String moduleIconKey,
        String title,
        String menuTitle,
        int menuOrder,
        String secondaryTitle,
        String uri,
        String contentTemplate,
        boolean showInMenu,
        boolean showAddButton,
        String buttonText,
        String modalIdPrefix
) {

    public ConsolePage withShowAddButton(boolean showAddButton) {
        return new ConsolePage(
                moduleKey,
                moduleTitle,
                moduleOrder,
                moduleIconKey,
                title,
                menuTitle,
                menuOrder,
                secondaryTitle,
                uri,
                contentTemplate,
                showInMenu,
                showAddButton,
                buttonText,
                modalIdPrefix
        );
    }
}
