package com.github.jkutner.boinc;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.xembly.Directives;
import org.xembly.ImpossibleModificationException;
import org.xembly.Xembler;

import java.io.*;
import java.net.URL;
import java.util.*;

public class BoincApp {

  public static final String DEFAULT_WRAPPER_VERSION="26014";

  public static final String MJAVA_VERSION="v0.1";

  private static String[] defaultPlatforms = new String[] {
      "x86_64-apple-darwin",
      "i686-apple-darwin",
      "windows_intelx86",
      "windows_x86_64",
      "i686-pc-linux-gnu",
      "x86_64-pc-linux-gnu"
  };

  private Set<String> platforms;

  private File boincDir = new File(System.getProperty("user.dir"), "boinc");

  private File srcUberjar;

  private File srcJobXml;

  private File srcTemplatesDir;

  private String versionKey;

  private File targetDir;

  private String assimilatorClass;

  public BoincApp(
      File uberjar,
      Map<String,Boolean> altPlatforms,
      File jobXml,
      File templatesDir,
      String versionKey,
      File targetDir,
      String assimilatorClass
  ) {
    platforms = new HashSet<String>();
    for (String p : altPlatforms.keySet())
      if (altPlatforms.get(p)) platforms.add(p);

    for (String p : defaultPlatforms)
      if (!altPlatforms.containsKey(p) || altPlatforms.get(p)) platforms.add(p);

    this.srcUberjar = uberjar;
    this.srcJobXml = jobXml;
    this.srcTemplatesDir = templatesDir;
    this.versionKey = versionKey == null ? UUID.randomUUID().toString() : versionKey;
    this.targetDir = targetDir;
    this.assimilatorClass = assimilatorClass;
  }

  public void cleanBoincDir(Boolean keepWrapper) throws IOException {
    if (this.boincDir.exists()) {
      if (keepWrapper) {
        for (File f : FileUtils.listFiles(this.boincDir, new WrapperFilter(), TrueFileFilter.INSTANCE)) {
          if (!f.isDirectory()) {
            FileUtils.forceDelete(f);
          }
        }
      } else {
        FileUtils.deleteDirectory(this.boincDir);
      }
    }
  }

  private static class WrapperFilter implements IOFileFilter {
    public boolean accept(File file) {
      return !"zip".equals(FilenameUtils.getExtension(file.getName()));
    }

    public boolean accept(File file, String s) {
      return !"zip".equals(FilenameUtils.getExtension(s));
    }
  }

  public void packageIntoBoincDir() throws IOException, ImpossibleModificationException, ZipException {
    cleanBoincDir(true);

    FileUtils.forceMkdir(boincDir);
    File appDir = initDir("app");
    File binDir = initDir("bin");
    copyDir("templates");

    String uberjarName = this.srcUberjar.getName();
    String uberjarPhysicalName = FilenameUtils.getBaseName(this.srcUberjar.getName())+"_"+this.versionKey+".jar";

    writeDaemonsXml(binDir, uberjarPhysicalName);

    for (String p : platforms) {
      Map<String,File> files = new HashMap<String, File>();

      File platformDir = new File(appDir, p);
      FileUtils.forceMkdir(platformDir);

      File uberjar = new File(platformDir, uberjarPhysicalName);
      FileUtils.copyFile(this.srcUberjar, uberjar);

      files.put(uberjarName, uberjar);
      files.put("job.xml", copyJobXml(platformDir, p, uberjarName));
      files.put("wrapper", installWrapper(platformDir, p));
      files.put(getJavaCmd(p), installMJava(platformDir, p));
      createVersionFile(platformDir, files);
      createComposerJson();
    }
  }

  protected File initDir(String dirName) throws IOException {
    File dir = new File(boincDir, dirName);
    FileUtils.forceMkdir(dir);
    return dir;
  }

  protected void copyDir(String dir) throws IOException {
    if (this.srcTemplatesDir.exists()) {
      File templatesDir = new File(boincDir, dir);
      FileUtils.copyDirectory(this.srcTemplatesDir, templatesDir);
    }
  }

  protected void createAssimilatorScript(File binDir, String uberjarPhysicalName) throws IOException {
    File scriptFile = new File(binDir, "java_assimilator");
    try (
        InputStream is = getClass().getResourceAsStream("/java_assimilator.sh");
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        FileWriter fw = new FileWriter(scriptFile);
        BufferedWriter out = new BufferedWriter(fw);
    ) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.replace("%uberjar_name%", uberjarPhysicalName);
        line = line.replace("%java_opts%", BoincAssimilator.buildJavaOpts(this.assimilatorClass));
        line = line.replace("%assimilator_class%", this.assimilatorClass);
        out.write(line);
        out.write("\n");
      }
    }
  }

  protected void writeDaemonsXml(File binDir, String uberjarPhysicalName) throws ImpossibleModificationException, IOException {
    File daemonsFile = new File(boincDir, "daemons.xml");
    Directives directives = new Directives().add("daemons")
        .add("daemon").add("cmd").set("feeder -d 3").up().up()
        .add("daemon").add("cmd").set("transitioner -d 3").up().up()
        .add("daemon").add("cmd").set("file_deleter -d 2 --preserve_wu_files --preserve_result_files").up().up();

    directives.add("daemon").add("cmd").set("sample_trivial_validator -d 2 --app ${HEROKU_APP_NAME}").up().up();

    if (this.assimilatorClass != null) {
      directives.add("daemon").add("cmd").set("script_assimilator --script java_assimilator -d 2 --app ${HEROKU_APP_NAME}").up().up();
      createAssimilatorScript(binDir, uberjarPhysicalName);
    } else {
      directives.add("daemon").add("cmd").set("sample_assimilator -d 2  --app ${HEROKU_APP_NAME}").up().up();
    }

    String xml = new Xembler(directives).xml();
    String xmlWithoutHeader = xml.substring(xml.indexOf('\n')+1);

    FileUtils.writeStringToFile(daemonsFile, xmlWithoutHeader);
  }

  protected File copyJobXml(File platformDir, String platform, String uberjarName)
      throws ImpossibleModificationException, IOException {
    String xml = new Xembler(new Directives().add("job_desc")
        .add("task")
        .add("application").set(getJavaCmd(platform)).up()
        .add("command_line").set("-jar " + uberjarName).up()
        .add("append_cmdline_args")
    ).xml();

    String jobFilename = "job_"+platform+"_"+this.versionKey+".xml";
    File jobFile = new File(platformDir, jobFilename);
    FileUtils.writeStringToFile(jobFile, xml);
    return jobFile;
  }

  protected File installWrapper(File platformDir, String platform) throws IOException, ZipException {
    String wrapperZipFilename = wrapperName(platform) + ".zip";
    String urlString = System.getProperty(
        "boinc.wrapper." + platform + ".url",
        "http://boinc.berkeley.edu/dl/" + wrapperZipFilename);
    installZipFile(platformDir, wrapperZipFilename, urlString);
    return new File(platformDir, wrapperName(platform)+exeExtension(platform));
  }

  protected void createVersionFile(File platformDir, Map<String,File> files)
      throws ImpossibleModificationException, IOException {
    Directives version = new Directives().add("version");

    for (String logicalName : files.keySet()) {
      File physicalFile = files.get(logicalName);
      Directives fileXml = version.add("file")
          .add("physical_name").set(physicalFile.getName()).up()
          .add("copy_file").up();
      if (logicalName.equals("wrapper")) {
        fileXml.add("main_program").up();
      } else {
        fileXml.add("logical_name").set(logicalName).up();
      }
      fileXml.up();
    }

    String xml = new Xembler(version).xml();
    File versionFile = new File(platformDir, "version.xml");
    FileUtils.writeStringToFile(versionFile, xml);
  }

  protected void createComposerJson() throws IOException {
    File composerJson = new File(System.getProperty("usr.dir"), "composer.json");
    if (!composerJson.exists())
      FileUtils.writeStringToFile(composerJson, "{}");
  }

  protected String wrapperName(String platform) {
    String wrapperVersion = System.getProperty("boinc.wrapper.version", wrapperVersion(platform));
    return "wrapper_"+wrapperVersion+"_"+platform;
  }

  protected String wrapperVersion(String platform) {
    if (platform.startsWith("windows_"))
      return "26016";
    return DEFAULT_WRAPPER_VERSION;
  }

  protected String exeExtension(String platform) {
    if (platform.startsWith("windows_"))
      return ".exe";
    return "";
  }

  protected String getJavaCmd(String platform) {
    if (platform.startsWith("windows_"))
      return "mjava.exe";
    return "mjava";
  }

  protected File installMJava(File platformDir, String platform) throws IOException, ZipException {
    String zipFilename = "mjava_" + platform + ".zip";
    String url = "https://github.com/jkutner/mjava/releases/download/"+MJAVA_VERSION+"/"+zipFilename;
    installZipFile(platformDir, zipFilename, url);
    File mjavaExe = new File(platformDir, getJavaCmd(platform));
    File mjavaExePhysical = new File(platformDir, mjavaName(platform));
    FileUtils.moveFile(mjavaExe, mjavaExePhysical);
    mjavaExePhysical.setExecutable(true, false);
    return mjavaExePhysical;
  }

  protected String mjavaName(String platform) {
    return "mjava_"+MJAVA_VERSION+"_" + platform + exeExtension(platform);
  }

  protected void installZipFile(File platformDir, String zipFilename, String urlString) throws IOException, ZipException {
    File zipFile = new File(this.targetDir, zipFilename);

    if (zipFile.exists()) {
      System.out.println("Using cached " + zipFilename + "...");
    } else {
      System.out.println("Downloading " + zipFilename + "...");
      URL wrapperUrl = new URL(urlString);
      FileUtils.copyURLToFile(wrapperUrl, zipFile);
    }

    System.out.println("Extracting " + zipFilename + "...");
    ZipFile zip = new ZipFile(zipFile);
    zip.extractAll(platformDir.toString());
  }
}
