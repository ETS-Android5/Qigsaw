/*
 * MIT License
 *
 * Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.iqiyi.android.qigsaw.core.extension;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.text.TextUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

final class AABExtensionManagerImpl implements AABExtensionManager {

    private final Context context;

    private SplitComponentInfoProvider infoProvider;

    private List<String> splitActivities;

    private List<String> splitServices;

    private List<String> splitReceivers;

    AABExtensionManagerImpl(Context context, SplitComponentInfoProvider infoProvider) {
        this.context = context;
        this.infoProvider = infoProvider;
    }

    @Override
    @SuppressLint("PrivateApi")
    public Application createApplication(String splitName) throws AABExtensionException {
        Exception error = null;
        String applicationName = infoProvider.getSplitApplicationName(splitName);
        if (!TextUtils.isEmpty(applicationName)) {
            try {
                Class<?> appClass = context.getClassLoader().loadClass(applicationName);
                return (Application) appClass.newInstance();
            } catch (ClassNotFoundException e) {
                error = e;
            } catch (InstantiationException e) {
                error = e;
            } catch (IllegalAccessException e) {
                error = e;
            }
        }
        if (error != null) {
            throw new AABExtensionException(error);
        }
        return null;
    }

    @Override
    @SuppressLint("PrivateApi")
    public void activeApplication(Application app) throws AABExtensionException {
        if (app != null) {
            Exception error = null;
            try {
                Method method = Application.class.getDeclaredMethod("attach", Context.class);
                method.setAccessible(true);
                method.invoke(app, context);
            } catch (NoSuchMethodException e) {
                error = e;
            } catch (IllegalAccessException e) {
                error = e;
            } catch (InvocationTargetException e) {
                error = e;
            }
            if (error != null) {
                throw new AABExtensionException(error);
            }
        }
    }

    @Override
    public boolean isSplitActivities(String name) {
        if (getSplitActivities() != null) {
            return getSplitActivities().contains(name);
        }
        return false;
    }

    @Override
    public boolean isSplitServices(String name) {
        if (getSplitServices() != null) {
            return getSplitServices().contains(name);
        }
        return false;
    }

    @Override
    public boolean isSplitReceivers(String name) {
        if (getSplitReceivers() != null) {
            return getSplitReceivers().contains(name);
        }
        return false;
    }

    private List<String> getSplitActivities() {
        if (splitActivities == null) {
            splitActivities = infoProvider.getSplitActivities();
        }
        return splitActivities;
    }

    private List<String> getSplitServices() {
        if (splitServices == null) {
            splitServices = infoProvider.getSplitServices();
        }
        return splitServices;
    }

    private List<String> getSplitReceivers() {
        if (splitReceivers == null) {
            splitReceivers = infoProvider.getSplitReceivers();
        }
        return splitReceivers;
    }
}