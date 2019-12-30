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

package com.iqiyi.qigsaw.buildtool.gradle.compiling

import com.android.SdkConstants
import com.iqiyi.qigsaw.buildtool.gradle.internal.entity.SplitDetails
import com.iqiyi.qigsaw.buildtool.gradle.internal.entity.SplitInfo
import com.iqiyi.qigsaw.buildtool.gradle.internal.model.SplitDetailsProcessor
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.QigsawLogger
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.SplitDetailsParser
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.TopoSort
import com.iqiyi.qigsaw.buildtool.gradle.task.QigsawProcessOldApkTask
import com.iqiyi.qigsaw.buildtool.gradle.upload.SplitApkUploader
import com.iqiyi.qigsaw.buildtool.gradle.upload.SplitApkUploaderInstance
import org.gradle.api.Project

class SplitDetailsProcessorImpl implements SplitDetailsProcessor {

    final Project project

    final File oldApkOutputDir

    SplitDetailsProcessorImpl(Project project,
                              File oldApkOutputDir) {
        this.project = project
        this.oldApkOutputDir = oldApkOutputDir
    }

    @Override
    SplitDetails processSplitDetails(SplitDetails rawSplitDetails,
                                     Map<String, SplitInfo> splitInfoMap) {
        rawSplitDetails.splits = rearrangeSplits(splitInfoMap)
        File oldSplitJsonFile = new File(oldApkOutputDir, QigsawProcessOldApkTask.OUTPUT_NAME)
        if (oldSplitJsonFile.exists()) {
            QigsawLogger.w("Old Split json file is exist, try to update splits!")
            SplitDetails oldSplitDetails = SplitDetailsParser.readSplitDetails(oldSplitJsonFile)
            if (!hasSplitVersionChanged(oldSplitDetails.splits, rawSplitDetails.splits)) {
                QigsawLogger.w("No splits need to be updated, just using old split APKs!")
                oldSplitDetails.splits.each {
                    resetSplitApkForSplitInfo(rawSplitDetails.builtInUrlPrefix, it)
                }
                oldSplitDetails.updateModeButNoVersionChanged = true
                oldSplitDetails.updateMode = true
                return oldSplitDetails
            } else {
                rawSplitDetails.updateMode = true
                List<String> updateSplits = getAndProcessUpdateSplits(oldSplitDetails, rawSplitDetails)
                QigsawLogger.w("Splits ${updateSplits} need to be updated!")
                rawSplitDetails.qigsawId = oldSplitDetails.qigsawId
                rawSplitDetails.updateSplits = updateSplits
            }
        }
        for (SplitInfo splitInfo : rawSplitDetails.splits) {
            uploadSplitApkIfNeed(rawSplitDetails.builtInUrlPrefix, rawSplitDetails.updateMode, splitInfo)
        }
        return rawSplitDetails
    }

    @Override
    void uploadSplitApkIfNeed(String builtInUrlPrefix, boolean updateMode, SplitInfo splitInfo) throws Exception {
        if (!splitInfo.builtIn) {
            SplitApkUploader uploader = SplitApkUploaderInstance.get()
            if (uploader == null) {
                QigsawLogger.e("SplitApkUploader has not been implemented, just make split " + splitInfo.splitName + " built-in")
            } else {
                String uploadedUrl = uploader.uploadSync(project, splitInfo.splitApk, splitInfo.splitName)
                if (uploadedUrl != null && uploadedUrl.startsWith("http")) {
                    splitInfo.url = uploadedUrl
                    QigsawLogger.w("Split ${splitInfo.splitName} apk file has been uploaded, see ${uploadedUrl}")
                    return
                } else {
                    QigsawLogger.e("Split ${splitInfo.splitName} upload failed! url: ${uploadedUrl}")
                }
            }
        }
        if (updateMode && splitInfo.builtIn) {
            resetSplitApkForSplitInfo(builtInUrlPrefix, splitInfo)
        }
        splitInfo.builtIn = true
        splitInfo.url = "${builtInUrlPrefix}${splitInfo.splitName + SdkConstants.DOT_ZIP}"
    }

    void resetSplitApkForSplitInfo(String builtInUrlPrefix, SplitInfo splitInfo) {
        String oldSplitApkFileName = builtInUrlPrefix.equals("assets://") ? splitInfo.splitName + SdkConstants.DOT_ZIP
                : "libsplit_${splitInfo.splitName + SdkConstants.DOT_NATIVE_LIBS}"
        File oldSplitApkFile = new File(oldApkOutputDir, oldSplitApkFileName)
        if (oldSplitApkFile.exists()) {
            QigsawLogger.w("Using old split apk ${oldSplitApkFile.absolutePath} for copying!")
            splitInfo.splitApk = oldSplitApkFile
        }
    }

    static List<SplitInfo> rearrangeSplits(Map<String, SplitInfo> splitInfoMap) {
        Map<String, TopoSort.Node> nodeMap = new HashMap<>()
        TopoSort.Graph graph = new TopoSort.Graph()
        Collection<SplitInfo> allSplits = splitInfoMap.values()
        for (SplitInfo info : allSplits) {
            if (nodeMap.get(info.splitName) == null) {
                nodeMap.put(info.splitName, new TopoSort.Node(info))
            }
            if (info.dependencies != null) {
                for (String dependency : info.dependencies) {
                    if (nodeMap.get(dependency) == null) {
                        nodeMap.put(dependency, new TopoSort.Node(splitInfoMap.get(dependency)))
                    }
                    graph.addNode(nodeMap.get(info.splitName), nodeMap.get(dependency))
                }
            }
        }
        TopoSort.KahnTopo topo = new TopoSort.KahnTopo(graph)
        topo.process()
        List<SplitInfo> splits = new ArrayList<>()
        for (int i = topo.result.size() - 1; i >= 0; i--) {
            SplitInfo info = topo.result.get(i).val
            splitInfoMap.remove(info.splitName)
            splits.add(info)
        }
        splits.addAll(splitInfoMap.values())
        return splits
    }

    private static boolean hasSplitVersionChanged(List<SplitInfo> oldSplits, List<SplitInfo> splits) {
        boolean versionChanged = false
        if (oldSplits != null) {
            for (SplitInfo info : splits) {
                for (SplitInfo oldInfo : oldSplits) {
                    if (info.splitName.equals(oldInfo.splitName)) {
                        if (!info.version.equals(oldInfo.version)) {
                            versionChanged = true
                        }
                    }
                }
            }
        }
        return versionChanged
    }

    private static List<String> getAndProcessUpdateSplits(SplitDetails oldSplitDetails, SplitDetails newSplitDetails) {
        List<String> updateSplits = null
        if (oldSplitDetails != null && oldSplitDetails.splits != null) {
            for (SplitInfo newInfo : newSplitDetails.splits) {
                for (SplitInfo oldInfo : oldSplitDetails.splits) {
                    if (newInfo.splitName.equals(oldInfo.splitName)) {
                        if (newInfo.version.equals(oldInfo.version)) {
                            QigsawLogger.w("Built-in split ${newInfo.splitName} version ${newInfo.version} is not changed, using old info!")
                            newInfo.copySplitInfo(oldInfo)
                        } else {
                            if (updateSplits == null) {
                                updateSplits = new ArrayList<>()
                            }
                            QigsawLogger.w("Split ${newInfo.splitName} version ${newInfo.version} is changed, it need to be updated!")
                            newInfo.builtIn = false
                            updateSplits.add(newInfo.splitName)
                        }
                    }
                }
            }
        }
        return updateSplits
    }
}