package com.deloitte;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.pdf.BadPdfFormatException;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfSmartCopy;
import com.sforce.soap.enterprise.*;
import com.sforce.soap.enterprise.Error;
import com.sforce.soap.enterprise.sobject.ContentVersion;
import com.sforce.soap.enterprise.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MergeAndUploadPDF {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(MergeAndUploadPDF.class);
    static EnterpriseConnection connection;
    private static final ExecutorService THREADPOOL = Executors.newCachedThreadPool();

    // queries and displays the 5 newest contacts
    public static void mergeanduploadPDF(String file1Ids, String parentId, String accessToken, String instanceURL, boolean useSoap) {

        LOGGER.info("Querying for the mail request...");

        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(accessToken);
        if (useSoap) {
            config.setServiceEndpoint(instanceURL + "/services/Soap/c/40.0");
        } else {
            config.setServiceEndpoint(instanceURL + "/services/Soap/T/40.0");
        }

        List<File> inputFiles = new ArrayList<File>();

        try {

            THREADPOOL.execute(new Runnable() {
                @Override
                public void run() {
                    String[] split = file1Ids.split(",");
                    StringBuilder buff = new StringBuilder();
                    String sep = "";
                    for (String str : split) {
                        if(str != parentId) {
                            buff.append(sep);
                            buff.append("'"+str+"'");
                            sep = ",";
                        }
                    }
                    String queryIds = buff.toString();
                    LOGGER.info("queryIds - > "+queryIds);
                    LOGGER.info("parentId - > "+parentId);
                    try {
                        connection = Connector.newConnection(config);
                        QueryResult queryResults = connection.query(
                                "Select Id,VersionData from ContentVersion where Id IN (Select LatestPublishedVersionId from ContentDocument where Id IN ("
                                        + queryIds + "))");

                        boolean done = false;

                        if (queryResults.getSize() > 0) {
                            while (!done) {
                                for (SObject sObject : queryResults.getRecords()) {
                                    ContentVersion contentData = (ContentVersion) sObject;
                                    File tempFile = File.createTempFile("test_", ".pdf", null);
                                    try (OutputStream os = Files.newOutputStream(Paths.get(tempFile.toURI()))) {
                                        os.write(contentData.getVersionData());
                                    }
                                    inputFiles.add(tempFile);
                                }
                                if (queryResults.isDone()) {
                                    done = true;
                                } else {
                                    queryResults = connection.queryMore(queryResults.getQueryLocator());
                                }

                            }
                        }

                        Document PDFCombineUsingJava = new Document();
                        PdfSmartCopy copy = new PdfSmartCopy(PDFCombineUsingJava, new FileOutputStream("CombinedPDFDocument.pdf"));
                        PDFCombineUsingJava.open();
                        int number_of_pages = 0;
                        inputFiles.parallelStream().forEachOrdered(inputFile -> {
                            try {
                                createFiles(inputFile, number_of_pages, copy);
                            } catch (IOException | BadPdfFormatException e) {
                                e.printStackTrace();
                            }
                        });

                        PDFCombineUsingJava.close();
                        copy.close();
                        File mergedFile = new File("CombinedPDFDocument" + ".pdf");
                        mergedFile.createNewFile();

                        LOGGER.info("Creating ContentVersion record...");
                        ContentVersion[] record = new ContentVersion[1];
                        ContentVersion mergedContentData = new ContentVersion();
                        mergedContentData.setVersionData(readFromFile(mergedFile.getName()));
                        mergedContentData.setFirstPublishLocationId(parentId);
                        mergedContentData.setTitle("Merged Document");
                        mergedContentData.setPathOnClient("/CombinedPDFDocument.pdf");

                        record[0] = mergedContentData;


                        // create the records in Salesforce.com
                        SaveResult[] saveResults = connection.create(record);

                        // check the returned results for any errors
                        for (int i = 0; i < saveResults.length; i++) {
                            if (saveResults[i].isSuccess()) {
                                LOGGER.info(i + ". Successfully created record - Id: " + saveResults[i].getId());
                            } else {
                                Error[] errors = saveResults[i].getErrors();
                                for (int j = 0; j < errors.length; j++) {
                                    LOGGER.error("ERROR creating record: " + errors[j].getMessage());
                                }
                            }
                        }
                    } catch (ConnectionException | IOException | DocumentException e) {
                        e.printStackTrace();
                    }
                }

                private void createFiles(File inputFile, int number_of_pages, PdfSmartCopy copy) throws IOException, BadPdfFormatException {
                    PdfReader ReadInputPDF = new PdfReader(inputFile.toString());
                    number_of_pages = ReadInputPDF.getNumberOfPages();
                    for (int page = 0; page < number_of_pages; ) {
                        copy.addPage(copy.getImportedPage(ReadInputPDF, ++page));
                    }
                    copy.freeReader(ReadInputPDF);
                    ReadInputPDF.close();
                }
            });


        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static byte[] readFromFile(String fileName) throws IOException {
        byte[] buf = new byte[8192];
        try (InputStream is = Files.newInputStream(Paths.get(fileName))) {
            int len = is.read(buf);
            if (len < buf.length) {
                return Arrays.copyOf(buf, len);
            }
            try (ByteArrayOutputStream os = new ByteArrayOutputStream(16384)) {
                while (len != -1) {
                    os.write(buf, 0, len);
                    len = is.read(buf);
                }
                return os.toByteArray();
            }
        }
    }

    // split 1 pdf file and get first page out of it
    public static void splitanduploadPDF(String documentId, String parentId, String accessToken, String instanceURL, boolean useSoap) {

        try {

            LOGGER.info("Querying for the mail request...");

            ConnectorConfig config = new ConnectorConfig();
            config.setSessionId(accessToken);
            if (useSoap) {
                config.setServiceEndpoint(instanceURL + "/services/Soap/c/40.0");
            } else {
                config.setServiceEndpoint(instanceURL + "/services/Soap/T/40.0");
            }
            connection = Connector.newConnection(config);

            // query for the attachment data
            QueryResult queryResults = connection.query(
                    "Select Id,VersionData from ContentVersion where Id IN(Select LatestPublishedVersionId from ContentDocument where Id = '"
                            + documentId + "')");
            File tempFile = File.createTempFile("test_", ".pdf", null);
            for (int i = 0; i < queryResults.getSize(); i++) {
                ContentVersion contentData = (ContentVersion) queryResults.getRecords()[i];
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(contentData.getVersionData());
                }
            }
            PdfReader Split_PDF_Document = new PdfReader(tempFile.toString());
            Document document;
            document = new Document();
            String FileName = "File" + 1 + ".pdf";
            PdfSmartCopy copy = new PdfSmartCopy(document, new FileOutputStream(FileName));
            document.open();
            copy.addPage(copy.getImportedPage(Split_PDF_Document, 1));
            copy.close();
            document.close();
            Split_PDF_Document.close();
            File splitFile = new File(FileName);
            splitFile.createNewFile();

            LOGGER.info("Creating ContentVersion record...");
            ContentVersion[] record = new ContentVersion[1];
            ContentVersion splitContentData = new ContentVersion();

            InputStream is = new FileInputStream(splitFile);
            splitContentData.setVersionData(IOUtils.toByteArray(is));

            is.close();
            splitContentData.setFirstPublishLocationId(parentId);
            splitContentData.setTitle("Split Document");
            splitContentData.setPathOnClient(FileName);

            record[0] = splitContentData;

            // create the records in Salesforce.com
            SaveResult[] saveResults = connection.create(record);

            // check the returned results for any errors
            for (int i = 0; i < saveResults.length; i++) {
                if (saveResults[i].isSuccess()) {
                    LOGGER.info(i + ". Successfully created record - Id: " + saveResults[i].getId());
                } else {
                    Error[] errors = saveResults[i].getErrors();
                    for (int j = 0; j < errors.length; j++) {
                        LOGGER.info("ERROR creating record: " + errors[j].getMessage());
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
    
    public static void mergeanduploadPDF(String file1Ids, String parentId, String pageNumber, String accessToken, String instanceURL, boolean useSoap) {

        LOGGER.info("Querying for the mail request...");

        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(accessToken);
        if (useSoap) {
            config.setServiceEndpoint(instanceURL + "/services/Soap/c/40.0");
        } else {
            config.setServiceEndpoint(instanceURL + "/services/Soap/T/40.0");
        }

        List<File> inputFiles = new ArrayList<File>();

        try {

            THREADPOOL.execute(new Runnable() {
                @Override
                public void run() {
                    String[] split = file1Ids.split(",");
                    StringBuilder buff = new StringBuilder();
                    String sep = "";
                    for (String str : split) {
                        if(str != parentId) {
                            buff.append(sep);
                            buff.append("'"+str+"'");
                            sep = ",";
                        }
                    }
                    String queryIds = buff.toString();
                    LOGGER.info("queryIds - > "+queryIds);
                    LOGGER.info("parentId - > "+parentId);
                    try {
                        connection = Connector.newConnection(config);
                        QueryResult queryResults = connection.query(
                                "Select Id,VersionData from ContentVersion where Id IN (Select LatestPublishedVersionId from ContentDocument where Id IN ("
                                        + queryIds + "))");

                        boolean done = false;

                        if (queryResults.getSize() > 0) {
                            while (!done) {
                                for (SObject sObject : queryResults.getRecords()) {
                                    ContentVersion contentData = (ContentVersion) sObject;
                                    File tempFile = File.createTempFile("test_", ".pdf", null);
                                    try (OutputStream os = Files.newOutputStream(Paths.get(tempFile.toURI()))) {
                                        os.write(contentData.getVersionData());
                                    }
                                    inputFiles.add(tempFile);
                                }
                                if (queryResults.isDone()) {
                                    done = true;
                                } else {
                                    queryResults = connection.queryMore(queryResults.getQueryLocator());
                                }

                            }
                        }

                        Document PDFCombineUsingJava = new Document();
                        PdfSmartCopy copy = new PdfSmartCopy(PDFCombineUsingJava, new FileOutputStream("CombinedPDFDocument.pdf"));
                        PDFCombineUsingJava.open();
                        int number_of_pages = 0;
                        inputFiles.parallelStream().forEachOrdered(inputFile -> {
                            try {
                                createFiles(inputFile, number_of_pages, Integer.parseInt(pageNumber), copy);
                            } catch (IOException | BadPdfFormatException e) {
                                e.printStackTrace();
                            }
                        });

                        PDFCombineUsingJava.close();
                        copy.close();
                        File mergedFile = new File("CombinedPDFDocument" + ".pdf");
                        mergedFile.createNewFile();

                        LOGGER.info("Creating ContentVersion record...");
                        ContentVersion[] record = new ContentVersion[1];
                        ContentVersion mergedContentData = new ContentVersion();
                        mergedContentData.setVersionData(readFromFile(mergedFile.getName()));
                        mergedContentData.setFirstPublishLocationId(parentId);
                        mergedContentData.setTitle("Merged Document");
                        mergedContentData.setPathOnClient("/CombinedPDFDocument.pdf");

                        record[0] = mergedContentData;


                        // create the records in Salesforce.com
                        SaveResult[] saveResults = connection.create(record);

                        // check the returned results for any errors
                        for (int i = 0; i < saveResults.length; i++) {
                            if (saveResults[i].isSuccess()) {
                                LOGGER.info(i + ". Successfully created record - Id: " + saveResults[i].getId());
                            } else {
                                Error[] errors = saveResults[i].getErrors();
                                for (int j = 0; j < errors.length; j++) {
                                    LOGGER.error("ERROR creating record: " + errors[j].getMessage());
                                }
                            }
                        }
                    } catch (ConnectionException | IOException | DocumentException e) {
                        e.printStackTrace();
                    }
                }

                private void createFiles(File inputFile, int number_of_pages, int pageNumber, PdfSmartCopy copy) throws IOException, BadPdfFormatException {
                    PdfReader ReadInputPDF = new PdfReader(inputFile.toString());
                    copy.addPage(copy.getImportedPage(ReadInputPDF, pageNumber));
                    copy.freeReader(ReadInputPDF);
                    ReadInputPDF.close();
                }
            });


        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
