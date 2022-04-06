package com.amend.utils;

import java.io.*;
import java.util.*;

/**
 * @author 程良明
 * @date 2022/3/31
 * * 说明: file 文件操作
 **/
public class FileUtils {
    private static final String KEY_HEX = "0x7f";
    private static final String KEY_WORK_SPACE = "workSpace";
    private static final String KEY_AMEND = "amend";
    private static final String KEY_ORIGINAL = "original";
    private static final String KEY_COLON = ":";
    private String mOriginalPath;
    private String mAmendPath;
    /**
     * 由resType和resName来确定id
     */
    private static Map<String, Map<String, String>> mTypeNameMap;
    /**
     * 修正后的文件
     */
    private List<File> mAmendFiles = new ArrayList<>();
    /**
     * 由旧的id映射到新的id
     */
    private Map<String, String> mValueMap = new HashMap<>();
    /**
     * 由旧的id映射到resType_resName
     */
    private Map<String, String> mOriginalMap = new HashMap<>();
    /**
     * 保存源来apk 就存在的id冲突，并通过这个来尝试修复，其实如果不使用的话问题不大
     */
    private Map<String, String> mMultipleOldId = new HashMap<>();
    /**
     * 工作路径，指apktool解压后的路径
     */
    private final String mWorkPath;
    private final String mPackageName;

    /**
     * 包名下的R文件映射
     */
    private Map<String, String> mPackageTemplate;

    private List<File> mRFiles = new ArrayList<>();
    private List<File> mOtherFiles = new ArrayList<>();

    private boolean usePackageTemplate = false;
    private boolean saveFiles = false;
    private boolean changeOtherFiles = false;

    public FileUtils(String workPath, String packageName, boolean saveFiles, boolean changeOtherFiles) {
        mWorkPath = workPath;
        mPackageName = packageName;
        this.saveFiles = saveFiles;
        this.changeOtherFiles = changeOtherFiles;

        if (mPackageName != null) {
            mPackageTemplate = new HashMap<>();
            usePackageTemplate = true;
        }

        mOriginalPath = linkPath(mWorkPath, KEY_WORK_SPACE, KEY_ORIGINAL);
        mAmendPath = linkPath(mWorkPath, KEY_WORK_SPACE, KEY_AMEND);
        File publicFile = new File(linkPath(workPath, "res", "values", "public.xml"));
        try {
            mTypeNameMap = XmlPullParsePublicXml.parsePublicXml(publicFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void execute() {

        delDir(new File(linkPath(mWorkPath, KEY_WORK_SPACE)));

        initAllFiles();

        generateAmendFiles();

        saveOriginalFiles();

        finish();
    }

    private void initAllFiles() {
        File workRootFile = new File(mWorkPath);
        for (File tempFile : Objects.requireNonNull(workRootFile.listFiles())) {
            if (tempFile.getName().startsWith("smali")) {
                parseFiles(tempFile);
            }
        }
    }

    private void generateAmendFiles() {
        generateRFile();

        if (changeOtherFiles) {
            generateOtherFiles();
        }
    }

    /**
     * 解析文件夹
     *
     * @param file 文件夹
     */
    private void parseFiles(File file) {
        if (file.isDirectory()) {
            File[] tempList = file.listFiles();
            assert tempList != null;
            for (File tempFile : tempList) {
                parseFiles(tempFile);
            }
        } else {
            if (file.getName().startsWith("R$")) {
                mRFiles.add(file);
            } else {
                mOtherFiles.add(file);
            }
        }
    }

    /**
     * 保存修改前的文件
     */
    private void saveOriginalFiles() {

        if (saveFiles) {
            if (changeOtherFiles) {
                List<File> needAmendFiles = new ArrayList<>();
                for (File tempFile : mAmendFiles) {
                    File needAmendFile = new File(tempFile.getPath().replace(mAmendPath, mWorkPath));
                    needAmendFiles.add(needAmendFile);
                }
//                needAmendFiles.addAll(mRFiles);
                copyOriginalFiles(needAmendFiles);
            } else {
                copyOriginalFiles(mRFiles);
            }
        }

    }


    private void generateRFile() {
        for (File tempFile : mRFiles) {
            String fileName = tempFile.getName();
            File tempOutPutFile = new File(tempFile.getPath().replace(mWorkPath, mAmendPath));
            createDirs(tempOutPutFile);
            int startIndex = fileName.lastIndexOf('$') + 1;
            int endIndex = fileName.lastIndexOf(".smali");
            String resType = fileName.substring(startIndex, endIndex);

            if (resType.equals("styleable")) {
                changeStyleableRFile(tempFile);
                continue;
            }

            Map<String, String> nameTypeMap = mTypeNameMap.get(resType);
            try (BufferedReader bufferedReader = new BufferedReader(new FileReader(tempFile));
                 BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(tempOutPutFile))) {
                String lineString = bufferedReader.readLine();
                while (lineString != null) {

                    String[] resSource = parseRFileLine(lineString);
                    if (resSource != null) {
                        String resName = resSource[0];
                        String resValue = resSource[1];
                        String affirmId = nameTypeMap.get(resName);
                        if (resValue != null) {
                            String typeAndName = String.format("%s:%s", resType, resName);
                            if (mValueMap.containsKey(resValue) && !mMultipleOldId.containsKey(affirmId)) {
                                String existId = mValueMap.get(resValue);
                                if (existId != null && affirmId != null && !existId.equals(affirmId)) {
                                    String existTypeName = mOriginalMap.get(resValue);
                                    String info = String.format("APK 可能存在Id %s  冲突,资源%s和%s", resValue, resName, existTypeName.split(KEY_COLON)[1]);
                                    System.err.println(info);

                                    mMultipleOldId.put(existId, existTypeName);
                                    mMultipleOldId.put(affirmId, typeAndName);
                                }

                            }
                            mOriginalMap.put(resValue, typeAndName);
                            mValueMap.put(resValue, affirmId);
                            if (usePackageTemplate) {
                                String key = linkPath(mPackageName.replace(".", File.separator), "R$");
                                if (tempFile.getPath().contains(key)) {
                                    mPackageTemplate.put(typeAndName, resValue);
                                }
                            }

                        }
                        lineString = amendLine(lineString, resValue, affirmId);
                    }

                    bufferedWriter.write(lineString + "\r\n");

                    lineString = bufferedReader.readLine();
                }
                bufferedWriter.flush();

            } catch (IOException e) {
                e.printStackTrace();
            }

            mAmendFiles.add(tempOutPutFile);
        }

    }

    /**
     * 拼接地址
     *
     * @param basePath 基础路径
     * @param dentrys  拼接路径
     * @return path
     */

    public String linkPath(String basePath, String... dentrys) {
        StringBuilder stringBuilder = new StringBuilder(basePath);
        for (String dentry : dentrys) {
            stringBuilder.append(File.separator);
            stringBuilder.append(dentry);
        }
        return stringBuilder.toString();
    }

    /**
     * 如果上一级目录不存在，则生成上一级目录文件夹
     *
     * @param file 文件
     */
    private void createDirs(File file) {
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
    }

    /**
     * 修改Styleable
     *
     * @param styleableFile file
     */
    private void changeStyleableRFile(File styleableFile) {
        File tempOutPutFile = new File(styleableFile.getPath().replace(mWorkPath, mAmendPath));
        createDirs(tempOutPutFile);
        generateAmendFile(styleableFile, tempOutPutFile);
    }

    /**
     * 解析R文件的一行
     *
     * @param line R文件的1行
     * @return string[0]是resName string[1]是resValue
     */
    private String[] parseRFileLine(String line) {
        String[] resSource = null;
        if (line.startsWith(".field public static final")) {
            String[] strings = line.split(KEY_COLON);
            String resName = strings[0].substring(strings[0].lastIndexOf(" ") + 1);
            String resValue = getHexString(strings[1]);
            resSource = new String[2];
            resSource[0] = resName;
            resSource[1] = resValue;
        }
        return resSource;
    }

    private String getHexString(String line) {
        String resValue = null;
        if (line.contains(KEY_HEX)) {
            int startIndex = line.indexOf(KEY_HEX);
            try {
                resValue = line.substring(startIndex, startIndex + 10);
            } catch (Exception ignored) {
                resValue = null;
            }
        }

        return resValue;
    }


    private String amendLine(String line, String sourceString, String targetString) {
        if (sourceString != null && targetString != null) {
            line = line.replace(sourceString, targetString);
        }
        return line;
    }

    /**
     * 使用映射值来修改文件
     *
     * @param tempFile       源文件
     * @param tempOutPutFile 输出文件
     */
    private void generateAmendFile(File tempFile, File tempOutPutFile) {
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(tempFile));
             BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(tempOutPutFile))) {

            String lineString = bufferedReader.readLine();
            while (lineString != null) {
//                        可能是旧的R文件值
                String resValue = getHexString(lineString);
                if (resValue != null) {
//                             从value 映射拿到最新的值，可能存在多个映射, 需要注意
                    String targetValue = mValueMap.get(resValue);
                    if (!tempFile.getName().equals("R$styleable.smali") && mMultipleOldId.containsKey(resValue)) {
                        System.err.println("Path： " + tempFile);
                        System.err.println("替换的资源id可能存在冲突，请悉知");
//                        使用包名下的文件的id来确定值
                        if (usePackageTemplate) {
                            String[] typeAndName = mPackageTemplate.get(resValue).split(KEY_COLON);
                            targetValue = mTypeNameMap.get(typeAndName[0]).get(typeAndName[1]);
                        }

                    }

                    lineString = amendLine(lineString, resValue, targetValue);
                }
                bufferedWriter.write(lineString + "\r\n");
                lineString = bufferedReader.readLine();
            }
            bufferedWriter.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
        mAmendFiles.add(tempOutPutFile);
    }


    /**
     * key 是原始的id值
     * value 是type_name
     */
    public void generateOtherFiles() {
        for (File tempFile : mOtherFiles) {
            File tempOutPutFile = new File(tempFile.getPath().replace(mWorkPath, mAmendPath));
            try (FileReader fileReader = new FileReader(tempFile)) {
                StringBuilder stringBuilder = new StringBuilder();
                char[] chars = new char[1024];
                int length;
                while ((length = fileReader.read(chars)) != -1) {
                    stringBuilder.append(chars, 0, length);
                }
                String fileCount = stringBuilder.toString();
                if (fileCount.contains(KEY_HEX)) {
                    createDirs(tempOutPutFile);
                    generateAmendFile(tempFile, tempOutPutFile);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * 将原来的文件保存一份
     */
    private void copyOriginalFiles(List<File> files) {
        for (File tempFile : files) {
            String outPutPath = tempFile.getPath().replace(mWorkPath, mOriginalPath);
            File outPutFile = new File(outPutPath);
            createDirs(outPutFile);
            copyOperation(tempFile, outPutFile);
        }
    }

    /**
     * 拷贝的具体操作
     *
     * @param tempFile   输入
     * @param outPutFile 输出
     */
    private void copyOperation(File tempFile, File outPutFile) {
        try (FileReader fileReader = new FileReader(tempFile);
             FileWriter fileWriter = new FileWriter(outPutFile)) {
            char[] chars = new char[1024];
            int length;
            while ((length = fileReader.read(chars)) != -1) {
                fileWriter.write(chars, 0, length);
            }
            fileWriter.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 替换修改后的文件为原来文件
     */
    private void finish() {

        for (File tempFile : mAmendFiles) {

            String outPutPath = tempFile.getPath().replace(mAmendPath, mWorkPath);
            File outPutFile = new File(outPutPath);
            copyOperation(tempFile, outPutFile);
        }

        if (!saveFiles) {
            delDir(new File(linkPath(mWorkPath, KEY_WORK_SPACE)));
        }
        System.out.println("Done!");

    }

    private void delDir(File file) {
        if (file.isDirectory()) {
            File[] list = file.listFiles();
            assert list != null;
            for (File f : list) {
                delDir(f);
            }
        }
        file.delete();
    }
}
