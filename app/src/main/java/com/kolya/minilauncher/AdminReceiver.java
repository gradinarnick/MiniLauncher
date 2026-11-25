package com.kolya.minilauncher;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** «Администратор устройства»: пока он включён, «Фокус» нельзя удалить обычным способом. */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Защита «Фокуса» от удаления будет снята. Делай это только с кодом владельца ключа.";
    }
}
