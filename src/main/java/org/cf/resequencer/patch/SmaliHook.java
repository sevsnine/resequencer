package org.cf.resequencer.patch;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cf.resequencer.Console;
import org.cf.resequencer.sequence.StringUtils;

/**
 * TODO: add static vars that house all methods then migrate code to use static vars
 * 
 * @author Caleb Fenton
 */
public class SmaliHook {
    /**
	 *
	 */
    public String ClassName;
    /**
	 *
	 */
    public String ClassMunge;
    /**
	 *
	 */
    public String Package;

    /*
     * If a hook is not to be obfuscated, this should be set to true.
     */
    public boolean ForceNoObfuscation = false;

    /*
     * Method of anti-decompile protection for baksmali (defunct) is to use invalid file names in Windows. Retained here
     * just in case...
     */
    private static ArrayList<String> InvalidWindowsFilenames = new ArrayList<String>();

    private SmaliFile MySmaliFile = new SmaliFile();

    /*
     * Maps original package, class and method name to original package, class and random method name. Does not include
     * parameters. Ex: Lpackage/Class;->foo : Lpackage/Class;->aslkdj3
     */
    /**
	 *
	 */
    public HashMap<String, String> MyMethods = new HashMap<String, String>();

    /**
	 *
	 */
    public HashMap<String, String> MyFields = new HashMap<String, String>();

    /**
	 *
	 */
    public static HashMap<String, String> AllClasses = new HashMap<String, String>();
    /**
	 *
	 */
    public static HashMap<String, String> AllPackages = new HashMap<String, String>();
    /**
	 *
	 */
    public static HashMap<String, String> AllMethods = new HashMap<String, String>();
    /**
	 *
	 */
    public static HashMap<String, String> AllFields = new HashMap<String, String>();

    SmaliHook(String fileLines) {
        this(fileLines, false);
    }

    SmaliHook(String fileLines, boolean forceNoObfuscation) {
        ForceNoObfuscation = forceNoObfuscation;

        if (InvalidWindowsFilenames.isEmpty()) {
            // "CLOCK$" is also invalid, but not good for replace/class
            Collections.addAll(InvalidWindowsFilenames, "CON", "PRN", "AUX", "NUL", "COM0", "COM1", "COM2", "COM3",
                            "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT0", "LPT1", "LPT2", "LPT3", "LPT4",
                            "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
        }

        MySmaliFile.FileLines = fileLines;

        // We collect this information so it's available to other hooks
        // that might call methods, etc. in this hook. We'll need to sort out
        // what all the new, obfuscated names for everything will be and replace
        // the old unobfuscated stuff.
        findPackage();
        findMethods();
        findFields();

        // Methods and fields have been munged, but the class
        // names stored with them also need to be updated.
        // They're used for ScriptVars.
        for (String methodCall : AllMethods.keySet()) {
            String methodMunge = AllMethods.get(methodCall);
            methodMunge = methodMunge.replace(Package + "/" + ClassName, AllPackages.get(Package) + "/" + ClassMunge);

            AllMethods.put(methodCall, methodMunge);
            MyMethods.put(methodCall, methodMunge);
        }

        for (String fieldCall : AllFields.keySet()) {
            String fieldMunge = AllFields.get(fieldCall);
            fieldMunge = fieldMunge.replace(Package + "/" + ClassName, AllPackages.get(Package) + "/" + ClassMunge);

            AllFields.put(fieldCall, fieldMunge);
            MyFields.put(fieldCall, fieldMunge);
        }

        MySmaliFile.FullFilePath = ClassName + ".smali";
        MySmaliFile.FileName = MySmaliFile.FullFilePath;
    }

    @Override
    public String toString() {
        return ClassName;
    }

    /**
     * 
     * @return
     */
    public String getFileLines() {
        return MySmaliFile.FileLines;
    }

    /**
     * 
     * @param lines
     */
    public void setFileLines(String lines) {
        MySmaliFile.FileLines = lines;
    }

    private void findPackage() {
        Pattern p = Pattern.compile("(?m)^[ \\t]*.class (public |private )?(final )?L(.+?);");
        Matcher m = p.matcher(getFileLines());

        if (!m.find()) {
            Console.die("Unable to find package for hook.");
            System.out.println(getFileLines());
        }

        // ex: com/package/Main
        Package = m.group(3);

        // ex: Main
        int pos1 = Package.lastIndexOf("/");
        ClassName = Package.substring(pos1 + 1);

        if (ForceNoObfuscation) {
            ClassMunge = ClassName;
        } else {
            ClassMunge = SmaliHook.getRandomClass();
        }

        // ex: com/package
        Package = Package.substring(0, pos1);

        if (!AllPackages.containsKey(Package)) {
            String packageMunge = "";
            if (ForceNoObfuscation) {
                packageMunge = Package;
            } else {
                packageMunge = getRandomPackage();
            }

            AllPackages.put(Package, packageMunge);
        }

        // ClassName may be split up amongst multiple files so check before adding
        if (!AllClasses.containsKey(Package + "/" + ClassName)) {
            AllClasses.put(Package + "/" + ClassName, AllPackages.get(Package) + "/" + ClassMunge);
        }
    }

    private void findMethods() {
        Pattern p = Pattern.compile("(?m)^[ \\t]*\\.method .+");
        Matcher m = p.matcher(getFileLines());

        String classPackage = "L" + Package + "/" + ClassName + ";";

        while (m.find()) {
            String matchedLine = getFileLines().substring(m.start(), m.end());

            // skip native, abstract and <init> <clinit> methods
            if (matchedLine.contains("native ") || matchedLine.contains("abstract ") || matchedLine.contains("<")) {
                continue;
            }

            String[] found = matchedLine.split("\\s");
            String methodCall = found[found.length - 1];
            methodCall = methodCall.substring(0, methodCall.indexOf("("));
            methodCall = classPackage + "->" + methodCall;

            if (MyMethods.containsKey(methodCall)) {
                continue;
            }

            Console.debug("found method: " + methodCall, 2);

            String methodMunge = "";
            if (ForceNoObfuscation) {
                methodMunge = methodCall;
            } else {
                methodMunge = SmaliHook.getRandomMethod();
                methodMunge = classPackage + "->" + methodMunge;
            }

            MyMethods.put(methodCall, methodMunge);
            AllMethods.put(methodCall, methodMunge);
        }
    }

    private void findFields() {
        // .field public a:Ljava/lang/String;
        Pattern p = Pattern.compile("(?m)^[ \\t]*\\.field .+");
        Matcher m = p.matcher(getFileLines());

        String classPackage = "L" + Package + "/" + ClassName + ";";

        while (m.find()) {
            String[] found = m.group().split("\\s");

            // .field static final synthetic $assertionsDisabled:Z = false
            String fieldCall;
            if (found[found.length - 2].equals("=")) {
                fieldCall = found[found.length - 3];
            } else {
                fieldCall = found[found.length - 1];
            }

            fieldCall = fieldCall.substring(0, fieldCall.indexOf(":"));
            fieldCall = classPackage + "->" + fieldCall;

            if (MyFields.containsKey(fieldCall)) {
                continue;
            }

            Console.debug("found field: " + fieldCall, 2);

            String fieldMunge = "";
            if (ForceNoObfuscation) {
                fieldMunge = fieldCall;
            } else {
                fieldMunge = classPackage + "->" + SmaliHook.getRandomField();
            }

            MyFields.put(fieldCall, fieldMunge);
            AllFields.put(fieldCall, fieldMunge);
        }
    }

    /**
	 *
	 */
    public void updateWithObfuscatedRefrences() {
        updatePackages();
        updateMethods();
        updateFields();
    }

    private void updatePackages() {
        for (String className : AllClasses.keySet()) {
            String classMunge = AllClasses.get(className);
            Console.debug("Renaming class: " + className + " to " + classMunge, 2);

            Pattern p = Pattern.compile("(?m)^[ \\t]*\\.class (public |private |protected )?" + "(static )?(final )?L"
                            + Pattern.quote(className) + ";");
            Matcher m = p.matcher(getFileLines());

            if (m.find() && !className.equals(classMunge)) {
                MySmaliFile.addReplace(m.start(), className, classMunge);
            }

            // there could be other places class name would need to be replaced
            // but not in my hooks
            p = Pattern.compile("(?m)^[ \\t]*const-class [vp]\\d+, L" + Pattern.quote(className) + ";");
            m = p.matcher(getFileLines());

            if (m.find() && !className.equals(classMunge)) {
                MySmaliFile.addReplace(m.start(), className, classMunge);
            }

            // Must perform modifications because file lines gets edited later
            MySmaliFile.doModifications();
        }
    }

    private void updateMethods() {
        for (String methodCall : AllMethods.keySet()) {
            String methodMunge = AllMethods.get(methodCall);
            String methodName = methodCall.substring(methodCall.indexOf("->") + 2);

            if (!getFileLines().contains(methodName)) {
                continue;
            }

            String methodMungeName = methodMunge.substring(methodMunge.indexOf("->") + 2);

            Pattern p;
            Matcher m;

            // only redefine methods if they're in this class
            if (methodCall.contains(Package + "/" + ClassName)) {
                p = Pattern.compile("(?m)^[ \\t]*\\.method (public |private |protected )?"
                                + "(static )?(final )?(synthetic )?" + Pattern.quote(methodName) + "\\(.$*");
                m = p.matcher(getFileLines());

                while (m.find() && !methodName.equals(methodMungeName)) {
                    // String rep = m.group().replace(methodName + "(", methodMungeName + "(");
                    Console.debug("Fixing method def: " + m.group().trim() + "\n" + "  with: " + methodMungeName + "(",
                                    3);
                    MySmaliFile.addReplace(m.start(), methodName + "(", methodMungeName + "(");
                }
            }

            p = Pattern.compile("(?m)^[ \\t]*invoke.*?" + Pattern.quote(methodCall) + "\\(.*$");
            m = p.matcher(getFileLines());

            // if munge and name are the same, might need to use start int
            while (m.find() && !methodCall.equals(methodMunge)) {
                // String rep = m.group().replace(methodCall + "(", methodMunge + "(");
                Console.debug("Fixing method invoke: " + m.group().trim() + "\n" + "  with: " + methodMunge + "(", 3);
                MySmaliFile.addReplace(m.start(), methodCall + "(", methodMunge + "(");
            }
        }
    }

    private void updateFields() {
        for (String fieldCall : AllFields.keySet()) {
            String fieldMunge = AllFields.get(fieldCall);
            String fieldName = fieldCall.substring(fieldCall.indexOf("->") + 2);

            Pattern p;
            Matcher m;

            if (fieldCall.contains(Package + "/" + ClassName)) {
                String fieldMungeName = fieldMunge.substring(fieldMunge.indexOf("->") + 2);
                p = Pattern.compile("(?m)^[ \\t]*\\.field (public |private |protected )?"
                                + "(static )?(final )?(synthetic )?" + Pattern.quote(fieldName) + ":.*");
                m = p.matcher(getFileLines());

                while (m.find() && !fieldName.equals(fieldMungeName)) {
                    Console.debug("  replacing field definition: " + m.group().trim() + "\n" + "  with: "
                                    + fieldMungeName, 3);
                    MySmaliFile.addReplace(m.start(), fieldName + ":", fieldMungeName + ":");
                }
            }

            p = Pattern.compile("(?m)^[ \\t]*\\S(put|get).*?" + Pattern.quote(fieldCall) + ":.*");
            m = p.matcher(getFileLines());

            // if munge and name are the same, might need to use start int
            while (m.find() && !fieldCall.equals(fieldMunge)) {
                Console.debug("  replacing field invoke: " + m.group().trim() + "\n" + "  with: " + fieldMunge, 3);
                MySmaliFile.addReplace(m.start(), fieldCall + ":", fieldMunge + ":");
            }
        }
    }

    /**
     * 
     * @param outPath
     */
    public void saveAs(String outPath) {
        // File name is too long. Shorten and add unique number.
        if (outPath.length() >= 199) {
            int offset = 0;
            // include directories and 16 characters of file
            int pos = outPath.lastIndexOf(File.separatorChar);
            outPath = outPath.substring(0, pos + 16) + ".smali";

            // make sure there are no collisions with file names
            while (new File(outPath).exists()) {
                outPath += "-" + ++offset;
            }
        } else if (InvalidWindowsFilenames.contains(MySmaliFile.FileName)) {
            // Uses an invalid windows file name, use something else.
            outPath = outPath.replace(".smali", "-.smali");
        }

        MySmaliFile.FullFilePath = outPath;
        File outFile = new File(MySmaliFile.FullFilePath);
        MySmaliFile.FileName = outFile.getName();

        MySmaliFile.doModificationsAndSave();
    }

    /**
     * 
     * @return
     */
    public static String getRandomPackage() {
        return StringUtils.generateAlphaString(1) + StringUtils.generateAlphaNumString(8, 12);
    }

    /**
     * 
     * @return
     */
    public static String getRandomClass() {
        /*
         * no longer using invalid windows file names. baksmali does not break. if ( InvalidWindowsFilenames.size() > 0
         * ) { Random rng = new Random(); int rndIndex = rng.nextInt(InvalidWindowsFilenames.size()); return
         * InvalidWindowsFilenames.remove(rndIndex); }
         */

        // Maximum file length is around 255
        // Cheap shot, yes I know. Fuck you.
        return StringUtils.generateAlphaString(1) + StringUtils.generateAlphaNumString(257, 257);
    }

    /**
     * 
     * @return
     */
    public static String getRandomMethod() {
        return StringUtils.generateAlphaString(1) + StringUtils.generateAlphaNumString(8, 18);
    }

    /**
     * 
     * @return
     */
    public static String getRandomField() {
        return StringUtils.generateAlphaString(1) + StringUtils.generateAlphaNumString(8, 18);
    }
}
