package com.hutao.touchkey;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.widget.RemoteViews;

public final class UnlockWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){
        String status=context.getSharedPreferences("widget-state",0).getString("status","点击开门");
        android.content.SharedPreferences selected=KeyProfiles.current(context);
        String name=selected.getString("name","默认门锁");
        status=name+" · "+status;
        for(int id:ids){
            RemoteViews views=new RemoteViews(context.getPackageName(),ht.key.R.layout.unlock_widget);
            Intent intent=new Intent(context,UnlockService.class);
            PendingIntent click=PendingIntent.getForegroundService(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            views.setTextViewText(ht.key.R.id.widget_status,status);
            views.setOnClickPendingIntent(ht.key.R.id.widget_open,click);
            manager.updateAppWidget(id,views);
        }
    }
    static void refreshAll(Context context){
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        new UnlockWidget().onUpdate(context,manager,manager.getAppWidgetIds(new ComponentName(context,UnlockWidget.class)));
    }
    static void status(Context context,String message){
        context.getSharedPreferences("widget-state",0).edit().putString("status",message).apply();
        refreshAll(context);
    }
    @Override public void onReceive(Context context,Intent intent){
        super.onReceive(context,intent);
        if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))refreshAll(context);
    }
}
