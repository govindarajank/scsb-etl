package org.recap.camel;

import io.swagger.models.auth.In;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.file.FileEndpoint;
import org.apache.camel.component.file.GenericFile;
import org.apache.camel.component.file.GenericFileFilter;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.processor.aggregate.AggregationStrategy;
import org.apache.commons.io.FilenameUtils;
import org.junit.Test;
import org.recap.BaseTestCase;
import org.recap.ReCAPConstants;
import org.recap.camel.activemq.JmxHelper;
import org.recap.camel.datadump.consumer.SolrSearchResultsProcessorActiveMQConsumer;
import org.recap.camel.datadump.consumer.MarcRecordFormatActiveMQConsumer;
import org.recap.camel.datadump.consumer.MarcXMLFormatActiveMQConsumer;
import org.recap.model.export.DataDumpRequest;
import org.recap.model.search.SearchRecordsRequest;
import org.recap.repository.BibliographicDetailsRepository;
import org.recap.repository.XmlRecordRepository;
import org.recap.service.DataDumpSolrService;
import org.recap.service.formatter.datadump.MarcXmlFormatterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by peris on 7/17/16.
 */

public class CamelJdbcUT extends BaseTestCase {

    @Value("${etl.split.xml.tag.name}")
    String xmlTagName;

    @Value("${etl.pool.size}")
    Integer etlPoolSize;

    @Value("${etl.pool.size}")
    Integer etlMaxPoolSize;

    @Value("${etl.max.pool.size}")
    String inputDirectoryPath;

    @Value("${activemq.broker.url}")
    String brokerUrl;

    @Autowired
    JmxHelper jmxHelper;

    @Autowired
    XmlRecordRepository xmlRecordRepository;

    @Autowired
    BibliographicDetailsRepository bibliographicDetailsRepository;

    @Autowired
    DataDumpSolrService dataDumpSolrService;

    @Autowired
    MarcXmlFormatterService marcXmlFormatterService;

    @Autowired
    private ProducerTemplate producer;

    @Value("${datadump.batch.size}")
    String dataDumpBatchSize;

    @Test
    public void parseXmlAndInsertIntoDb() throws Exception {


        camelContext.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                FileEndpoint fileEndpoint = endpoint("file:" + inputDirectoryPath, FileEndpoint.class);
                fileEndpoint.setFilter(new XmlFileFilter());

                from(fileEndpoint)
                        .split()
                        .tokenizeXML(xmlTagName)
                        .streaming()
                        .threads(etlPoolSize, etlMaxPoolSize, "xmlProcessingThread")
                        .process(new XmlProcessor(xmlRecordRepository))
                        .to("jdbc:dataSource");
            }
        });

        java.lang.Thread.sleep(10000);
    }

    class XmlFileFilter implements GenericFileFilter {
        @Override
        public boolean accept(GenericFile file) {
            return FilenameUtils.getExtension(file.getAbsoluteFilePath()).equalsIgnoreCase("xml");
        }
    }


    @Test
    public void exportDataDump() throws Exception {
        SearchRecordsRequest searchRecordsRequest = new SearchRecordsRequest();
        searchRecordsRequest.setOwningInstitutions(Arrays.asList("CUL"));
        searchRecordsRequest.setCollectionGroupDesignations(Arrays.asList("Shared"));
        searchRecordsRequest.setPageSize(Integer.valueOf(dataDumpBatchSize));

        long startTime = System.currentTimeMillis();
        Map results = dataDumpSolrService.getResults(searchRecordsRequest);

        DataDumpRequest dataDumpRequest = new DataDumpRequest();
        dataDumpRequest.setToEmailAddress("peri.subrahmanya@gmail.com");
        String dateTimeString = getDateTimeString();
        dataDumpRequest.setDateTimeString(dateTimeString);
        dataDumpRequest.setTransmissionType(ReCAPConstants.DATADUMP_TRANSMISSION_TYPE_FTP);
        dataDumpRequest.setInstitutionCodes(Arrays.asList("NYPL", "CUL"));

        long endTime = System.currentTimeMillis();
        System.out.println("Time taken to fetch 10K results for page 1 is : " + (endTime - startTime) / 1000 + " seconds ");
        String fileName = "PUL" + File.separator + dateTimeString + File.separator + ReCAPConstants.DATA_DUMP_FILE_NAME + "PUL" + 0;
        String folderName = "PUL" + File.separator + dateTimeString;

        Integer totalPageCount = (Integer) results.get("totalPageCount");

        String headerString = getBatchHeaderString(totalPageCount, 1, folderName, fileName, dataDumpRequest);

        producer.sendBodyAndHeader(ReCAPConstants.SOLR_INPUT_FOR_DATA_EXPORT_Q, results, "batchHeaders", headerString.toString());

        for (int pageNum = 1; pageNum < totalPageCount; pageNum++) {
            searchRecordsRequest.setPageNumber(pageNum);
            startTime = System.currentTimeMillis();
            Map results1 = dataDumpSolrService.getResults(searchRecordsRequest);
            endTime = System.currentTimeMillis();
            System.out.println("Time taken to fetch 10K results for page  : " + pageNum + " is " + (endTime - startTime) / 1000 + " seconds ");
            fileName = "PUL" + File.separator + dateTimeString + File.separator + ReCAPConstants.DATA_DUMP_FILE_NAME + "PUL" + pageNum + 1;
            headerString = getBatchHeaderString(totalPageCount, pageNum + 1, folderName, fileName, dataDumpRequest);
            producer.sendBodyAndHeader(ReCAPConstants.SOLR_INPUT_FOR_DATA_EXPORT_Q, results1, "batchHeaders", headerString.toString());
        }

        while (true) {

        }
    }

    private String getBatchHeaderString(Integer totalPageCount, Integer currentPageCount, String folderName, String fileName, DataDumpRequest dataDumpRequest) {
        StringBuilder headerString = new StringBuilder();
        headerString.append("totalPageCount")
                .append("#")
                .append(totalPageCount)
                .append(";")
                .append("currentPageCount")
                .append("#")
                .append(currentPageCount)
                .append(";")
                .append("folderName")
                .append("#")
                .append(folderName)
                .append(";")
                .append("fileName")
                .append("#")
                .append(fileName)
                .append(";")
                .append("institutionCodes")
                .append("#")
                .append(getInstitutionCodes(dataDumpRequest))
                .append(";")
                .append("fileFormat")
                .append("#")
                .append(dataDumpRequest.getFileFormat())
                .append(";")
                .append("transmissionType")
                .append("#")
                .append(dataDumpRequest.getTransmissionType())
                .append(";")
                .append("toEmailId")
                .append("#")
                .append(dataDumpRequest.getToEmailAddress())
                .append(";")
                .append("dateTimeString")
                .append("#")
                .append(dataDumpRequest.getDateTimeString())
                .append(";")
                .append("requestingInstitutionCode")
                .append("#")
                .append(dataDumpRequest.getRequestingInstitutionCode());

        return headerString.toString();
    }

    private String getInstitutionCodes(DataDumpRequest dataDumpRequest) {
        List<String> institutionCodes = dataDumpRequest.getInstitutionCodes();
        StringBuilder stringBuilder = new StringBuilder();
        for (Iterator<String> iterator = institutionCodes.iterator(); iterator.hasNext(); ) {
            String code = iterator.next();
            stringBuilder.append(code);
            if(iterator.hasNext()){
                stringBuilder.append("*");
            }
        }
        return stringBuilder.toString();
    }

    private String getDateTimeString() {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat(ReCAPConstants.DATE_FORMAT_MMDDYYY);
        return sdf.format(date);
    }
}
