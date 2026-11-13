package com.restaurantpos.ui;

import javafx.stage.Window;

public interface Navigator {
    void navigate(String key);
    void showToast(String message, String severity);
    void refreshNotifications();
    void previewReceipt(long orderId);
    void startOrder(long tableId);
    Window window();
}
