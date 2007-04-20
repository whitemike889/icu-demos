/*
 ******************************************************************************
 * Copyright (C) 2007-2007, International Business Machines Corporation and   *
 * others. All Rights Reserved.                                               *
 ******************************************************************************
 */
package com.ibm.icu.datacustom;

import java.io.*;
import java.util.*;

// logging
import java.util.logging.*;

// servlet imports
import javax.servlet.*;
import javax.servlet.http.*;

// DOM imports
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.*;

/**
 * The main servlet class of the Data Custimization tool
 */
public class DataCustomizer extends HttpServlet {

    // Logging
    public static Logger logger = Logger.getLogger("com.ibm.icu.DataCustomizer");
    
    public static final String NEWLINE = System.getProperty("line.separator");
    private static final String FILE_GET = "get";
    private static final String SESSION_ID = "id";

    /** status * */
    public static String toolHome;
    public static String toolHomeSrcDirStr;
    public static String toolHomeRequestDirStr;
    private static boolean DEBUG_FILES = false;

    ServletConfig config;
    DocumentBuilderFactory docBuilderFactory;

    public final void init(final ServletConfig config) throws ServletException {
        super.init(config);

        this.config = config;

        doStartup();
    }

    public DataCustomizer() {
        //docBuilder.setSchema();
    }

    /**
     * output MIME header, build context, and run code..
     */
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
    {
        String sessionID = request.getSession().getId();
        applyNoCache(response);

        // Parse the request.
        //PrintWriter writer = response.getWriter();
        // You can't use getInputStream and getReader
        //dumpRequest(request, writer);
        if (request.getContentType().startsWith("text/xml; ")) {
            String filesToPackage = "";
            String icuDataVersion;
            try {
                Document xmlDoc = getDocumentBuilder().parse(request.getInputStream());
                NodeList pkgItems = xmlDoc.getElementsByTagName("item");
                int itemsLen = pkgItems.getLength();
                for (int itemIdx = 0; itemIdx < itemsLen; itemIdx++) {
                    String pkgItemName = pkgItems.item(itemIdx).getFirstChild().getNodeValue();
                    filesToPackage += pkgItemName + NEWLINE;
                }
                icuDataVersion = xmlDoc.getElementsByTagName("version").item(0).getFirstChild().getNodeValue();
            }
            catch (ParserConfigurationException e) {
                reportError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                return;
            }
            catch (SAXException e) {
                reportError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                return;
            }
            File sessionDir = new File(toolHomeRequestDirStr + sessionID + File.separator);
            String sessionDirStr = sessionDir.getAbsolutePath() + File.separator;
            if (sessionDir.exists()) {
                String msg = sessionDirStr + " already exists.";
                logger.warning(msg);
                // TODO: What happens when a user submits a request twice and we haven't cleaned up?
                //response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg);
                //return;
            }
            if (!sessionDir.mkdir()) {
                String msg = sessionDirStr + " could not be created.";
                logger.warning(msg);
                // TODO: What happens when we run out space or it already exists?
                //response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg);
                //return;
            }
            // TODO: Clean up after ourselves in case of a problem.
            //sessionDir.deleteOnExit();
            String packageList;
            try {
                packageList = sessionDirStr + "package.lst";
                if ((new File(packageList)).exists()) {
                    reportError(response, HttpServletResponse.SC_CONFLICT, "Please finish the download of your existing request before creating a new request.");
                    return;
                }
                PrintWriter dataToPackageWriter = new PrintWriter(packageList);
                dataToPackageWriter.print(filesToPackage);
                dataToPackageWriter.close();
            }
            catch (IOException e) {
                reportError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not write package list in " + sessionDir.getAbsolutePath());
                return;
            }
            String baseDataName = "icudt" + icuDataVersion + "l"; // TODO: Fix this endianness
            String generatedDatFile = sessionDirStr + baseDataName + ".dat";
            String generatedZipFile = sessionDirStr + baseDataName + ".zip ";
            if ((new File(generatedZipFile)).exists()) {
                reportError(response, HttpServletResponse.SC_CONFLICT, "Please finish the download of your existing request before creating a new request.");
                return;
            }
            String pkgCommand = "icupkg -a " + packageList
                + " -s " + toolHomeSrcDirStr + baseDataName
                + " new " + generatedDatFile;
            if (!runCommand(response, pkgCommand, sessionDir, "Packaging tool")) {
                return;
            }
            /*
             * -1 Reduces CPU usage, and results in a zip file 3-5% larger than standard compression
             * -9 Increases CPU usage, and results in a zip file 0-1% smaller than standard compression
             */
            String compressCommand = "zip -1q " + generatedZipFile + baseDataName + ".dat";
            if (!runCommand(response, compressCommand, sessionDir, "Compression tool")) {
                return;
            }
            //writer.print(filesToPackage);
            /*response.setContentType("application/zip");
            InputStream inZip = new FileInputStream(generatedZipFile);
            OutputStream outStream = response.getOutputStream();
            byte []zipBytes = new byte[inZip.available()];
            inZip.read(zipBytes);
            outStream.write(zipBytes);*/
            
            // Redirect with the result.
            response.getWriter().print(request.getRequestURL() + "?" + FILE_GET + "=" + baseDataName + ".zip");

            if (!DEBUG_FILES) {
                (new File(generatedDatFile)).delete();
                (new File(packageList)).delete();
            }
        }
        else {
            PrintWriter writer = response.getWriter();
            dumpRequest(request, writer);
        }

        //response.setContentType("text/xml; charset=utf-8");
    }

    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
    {
        String sessionID = request.getSession(true).getId();
        applyNoCache(response);
        if (sessionID == null) {
            reportError(response, HttpServletResponse.SC_FORBIDDEN, "Session is stale");
            return;
        }
        //PrintWriter writer = response.getWriter();
        //dumpRequest(request, writer);
        String fileToSend = request.getParameter(FILE_GET);
        if (fileToSend == null || fileToSend.indexOf('/') != -1 || fileToSend.indexOf('\\') != -1) {
            reportError(response, HttpServletResponse.SC_BAD_REQUEST, fileToSend + " is not a valid requested file");
            return;
        }
        
        File generatedFile = new File(toolHomeRequestDirStr + sessionID + File.separator + fileToSend);
        if (!generatedFile.exists()) {
            reportError(response, HttpServletResponse.SC_GONE, fileToSend + " has already been downloaded.");
            return;
        }
        
        response.setContentType("application/zip");
        InputStream inZip = new FileInputStream(generatedFile);
        OutputStream outStream = response.getOutputStream();
        byte []zipBytes = new byte[inZip.available()];
        inZip.read(zipBytes);
        inZip.close();
        outStream.write(zipBytes);
        outStream.flush();
        if (!DEBUG_FILES) {
            generatedFile.delete();
            (new File(toolHomeRequestDirStr + sessionID)).delete();
        }

        //writer.println(fileToSend);
        //writer.println(generatedFile);
        //response.setContentType("text/html; charset=utf-8");
    }
    
    private void reportError(HttpServletResponse response, int httpStatus, String msg) throws IOException {
        logger.warning(msg);
        response.sendError(httpStatus, msg);
    }

    private boolean runCommand(HttpServletResponse response, String command, File execDir, String genericName)
        throws IOException
    {
        Process icupkg = Runtime.getRuntime().exec(command, null, execDir);
        try {
            icupkg.waitFor();
        }
        catch (InterruptedException e) {
            reportError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, genericName + " was interrupted.");
            return false;
        }
        BufferedReader icupkgErrOut = new BufferedReader(new InputStreamReader(icupkg.getErrorStream()));
        if (icupkg.exitValue() != 0) {
            //String msg = "\"" + command + "\" failed with the following message:";
            String msg = "";
            String outLine = "";
            while ((outLine = icupkgErrOut.readLine()) != null) {
                msg += outLine + "\n";
            }
            reportError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg);
            return false;
        }
        return true;
    }
    private String convertBufferedReaderToString(BufferedReader reader)
        throws IOException
    {
        StringBuffer contentBuf = new StringBuffer();
        String line;
        while ((line = reader.readLine()) != null) {
            contentBuf.append(line);
        }
        return contentBuf.toString();
    }

    private void dumpRequest(HttpServletRequest request, PrintWriter writer) 
        throws IOException
    {
        writer.println("<html>");
        writer.println("<head>");
        writer.println("<title>Data Request Dump</title>");
        writer.println("</head>");
        writer.println("<body>");

        writer.println("<table border=\"0\">");
        writer.println("<tr>");
        writer.println("<td>");
        writer.println("<h1>Data Request Dump</h1>");
        writer.println("<p>");
        writer.println("The following is diagnostic information of your request.");
        writer.println("</p>");
        writer.println("<p>");
        writer.println("The current home directory is " + System.getProperty("user.name"));
        writer.println("</p>");
        writer.println("</td>");
        writer.println("</tr>");
        writer.println("</table>");

        writer.println("<table border=\"1\">");
        writer.println("<caption>Header Information</caption>");
        Enumeration names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            writer.println("<tr>");
            writer.println("  <th align=\"right\">" + name + ":</th>");
            writer.println("  <td>" + request.getHeader(name) + "</td>");
            writer.println("</tr>");
        }
        writer.println("</table>");

        writer.println("<table border=\"1\">");
        writer.println("<caption>Request Information</caption>");
        HttpSession session = request.getSession();
        writer.println("<tr><td>Session ID</td><td>" + session.getId() + "</td></tr>");
        writer.println("</table>");

        writer.println("<table border=\"1\">");
        writer.println("<caption>Parameter Information</caption>");
        Enumeration paramNames = request.getParameterNames();
        while (paramNames.hasMoreElements()) {
            String name = (String) paramNames.nextElement();
            writer.println("<tr>");
            writer.println("  <th align=\"right\">" + name + ":</th>");
            writer.println("  <td>");
            String[] values = request.getParameterValues(name);
            for (int i = 0; i < values.length; i++) {
                writer.println(Integer.toString(i) + " " + values[i] + "<br />");
            }
            writer.println("</td>");
            writer.println("</tr>");
        }
        writer.println("</table>");

        writer.println("<table border=\"1\">");
        writer.println("<caption>Attribute Information</caption>");
        Enumeration attribNames = request.getAttributeNames();
        while (attribNames.hasMoreElements()) {
            String name = (String) attribNames.nextElement();
            writer.println("<tr>");
            writer.println("  <th align=\"right\">" + name + ":</th>");
            writer.println("  <td>" + request.getAttribute(name) + "</td>");
            writer.println("</tr>");
        }
        writer.println("</table>");

        
        writer.println("<samp><pre>");
        writer.println(convertBufferedReaderToString(request.getReader()));
        writer.println("</pre></samp>");
        writer.println("</body>");
        writer.println("</html>");
    }

    /**
     * Main setup
     */
    static boolean isSetup = false;

    public synchronized void doStartup() throws ServletException {
        if (isSetup == true) {
            return;
        }

        logger.info("Starting ICU Data Customization tool. Memory in use: "
                + usedKB() + "KB");
        isSetup = true;

        toolHome = config.getInitParameter("DATA_CUSTOM.home");
        if (toolHome == null) {
            toolHome = System.getProperty("user.home") + "datacustom" + File.separator;
        }

        logger.info(toolHome + " will be used for reading and writing data.");
        
        File toolHomeDir = new File(toolHome);
        if (!toolHomeDir.exists()) {
            if (toolHomeDir.mkdirs()) {
                logger.info(toolHomeDir.getAbsolutePath() + " was created.");
            }
            else {
                logger.warning(toolHomeDir.getAbsolutePath() + " could not be created.");
            }
        }
        if (!toolHomeDir.isDirectory() || !toolHomeDir.canRead()) {
            logger.warning(toolHomeDir.getAbsolutePath() + " is not a valid data directory.");
        }
        else {
            /* Check and create required subdirectories. */
            File toolHomeSrcDir = new File(toolHome + "source");
            toolHomeSrcDirStr = toolHomeSrcDir.getAbsolutePath() + File.separator;
            if (!toolHomeSrcDir.exists()) {
                logger.warning(toolHomeSrcDirStr + " does not exist.");
            }
            else if (!toolHomeSrcDir.isDirectory() || !toolHomeSrcDir.canRead()) {
                logger.warning(toolHomeSrcDirStr + " is not a valid directory.");
            }
            else if (toolHomeDir.canWrite()) {
                logger.warning(toolHomeSrcDirStr + " shouldn't be writable for security reasons.");
            }
            
            File toolHomeRequestDir = new File(toolHome + "request");
            toolHomeRequestDirStr = toolHomeRequestDir.getAbsolutePath() + File.separator;
            if (!toolHomeRequestDir.exists()) {
                if (!toolHomeRequestDir.mkdir()) {
                    logger.warning(toolHomeRequestDirStr + " could not be created.");
                }
            }
            if (!toolHomeRequestDir.isDirectory() || !toolHomeRequestDir.canRead() || !toolHomeRequestDir.canWrite()) {
                logger.warning(toolHomeRequestDirStr + " is not a valid directory.");
            }
        }
        
        try {
            docBuilderFactory = DocumentBuilderFactory.newInstance();
            /* TODO: Get the real location of the Schema file. */
            Source schemaFile = new StreamSource(new File("webapps/datacustom/datapackage.xsd"));
            /** A Schema is thread safe. A Validator is thread unsafe. */
            Schema dataRequestSchema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(schemaFile);
            docBuilderFactory.setSchema(dataRequestSchema);
        }
        catch (SAXException e) {
            logger.warning(e.getMessage());
        }
        
        logger.info("ICU Data Customization tool ready for requests. Memory in use: "
                        + usedKB() + "KB");
    }
    
    private void applyNoCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Max-Age", 0);
    }
    
    /**
     * DocumentBuilder and DocumentBuilderFactory are not thread safe.
     * Synchronized to ensure thread safety.
     */
    private synchronized DocumentBuilder getDocumentBuilder() throws ParserConfigurationException {
        return docBuilderFactory.newDocumentBuilder();
    }

    public void destroy() {
        logger.warning("ICU Data Customization tool shutting down.");
        super.destroy();
    }

    // ====== Utility Functions
    public static int usedKB() {
        Runtime r = Runtime.getRuntime();
        double total = r.totalMemory();
        total = total / 1024;
        double free = r.freeMemory();
        free = free / 1024;
        return (int) (Math.floor(total - free));
    }
}