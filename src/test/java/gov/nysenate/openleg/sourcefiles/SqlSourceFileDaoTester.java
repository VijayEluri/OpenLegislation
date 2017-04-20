package gov.nysenate.openleg.sourcefiles;

import gov.nysenate.openleg.BaseTests;
import gov.nysenate.openleg.config.Environment;
import gov.nysenate.openleg.dao.sourcefiles.SourceFileDao;
import gov.nysenate.openleg.dao.sourcefiles.sobi.SobiDao;
import gov.nysenate.openleg.dao.sourcefiles.xml.XmlDao;
import gov.nysenate.openleg.model.sourcefiles.SourceFile;
import gov.nysenate.openleg.model.sourcefiles.sobi.SobiFile;
import gov.nysenate.openleg.model.sourcefiles.xml.XmlFile;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by Robert Bebber on 4/3/17.
 */
@Transactional
public class SqlSourceFileDaoTester extends BaseTests {

    @Autowired
    SourceFileDao sourceFileDao;
    @Autowired
    SobiDao sobiDao;
    @Autowired
    XmlDao xmlDao;

    @Autowired Environment environment;
    private static final Logger logger = LoggerFactory.getLogger(SqlSourceFileDaoTester.class);


    public File preTestSetup(String sourceName, String archiveName, boolean makedir) throws IOException {
        File temp = new File(archiveName, sourceName);
        if (makedir) {
            temp.mkdirs();
        }
        temp.createNewFile();
        temp.deleteOnExit();
        return temp;
    }

    /**
     * Be Cautious of using this test if the file holding the XML multiple children
     * as this method deletes the parent director.
     *
     * @throws IOException
     */
    @Test
    public void testXmlFileArchive() throws IOException {
        String fileName = "2017-02-06-12.13.28.656756_LDSUMM_A04892.XML";
        String stagingDir = environment.getStagingDir() +"/xmls/";
        String archiveDir = environment.getArchiveDir() + "/xmls/2017/LDSUMM";
        File stagingFile = preTestSetup(fileName, stagingDir, false);
        File expectedArchiveFile = new File(archiveDir,fileName);
        File retrievedArchiveFile = null;
        try {
            SourceFile sourceFile = new XmlFile(stagingFile);
            xmlDao.archiveXmlFile(sourceFile);
            sourceFileDao.updateSourceFile(sourceFile);
            retrievedArchiveFile = sourceFileDao.getSourceFile(fileName).getFile();
            assertEquals("XML File Insert Retrieval",
                    expectedArchiveFile.getAbsolutePath(), retrievedArchiveFile.getAbsolutePath());
        } finally {
            cleanUpFiles(stagingFile, expectedArchiveFile, retrievedArchiveFile);
        }
    }

    @Test
    public void testSobiFileArchive() throws IOException {
        String fileName = "SOBI.D160000.T124521.TXT";
        String stagingDir = environment.getStagingDir() + "/sobis/";
        String archiveDir = environment.getArchiveDir() + "/sobis/2015";
        File stagingFile = preTestSetup(fileName, stagingDir, false);
        File expectedArchiveFile = new File(archiveDir,fileName);
        File retrievedArchiveFile = null;
        try {
            SourceFile sourceFile = new SobiFile(stagingFile);
            sobiDao.archiveSobiFile(sourceFile);
            sourceFileDao.updateSourceFile(sourceFile);
            retrievedArchiveFile = sourceFileDao.getSourceFile(fileName).getFile();
            assertEquals("Sobi File Insert Retrieval",
                    expectedArchiveFile.getAbsolutePath(), retrievedArchiveFile.getAbsolutePath());
        } finally {
            cleanUpFiles(stagingFile, expectedArchiveFile, retrievedArchiveFile);
        }
    }

    @Test
    public void testXmlFileDate() throws IOException {
        String fileName = "2017-02-06-12.13.28.656756_LDSUMM_A04892.XML";
        String archiveDir = "/data/openleg/archive/xmls/2017/LDSUMM";
        File archiveFile = preTestSetup(fileName, archiveDir, false);
        SourceFile retrievedArchiveFile = null;
        try {
            SourceFile sourceFile = new XmlFile(archiveFile);
            sourceFile.setArchived(true);
            sourceFileDao.updateSourceFile(sourceFile);
            retrievedArchiveFile = sourceFileDao.getSourceFile(fileName);
            assertEquals("Xml File Insert Retrieval",
                    sourceFile.getPublishedDateTime(), retrievedArchiveFile.getPublishedDateTime());
        } finally {
            cleanUpFiles(archiveFile, retrievedArchiveFile.getFile());
        }
    }

    @Test
    public void testSobiFileDate() throws IOException {
        String fileName = "SOBI.D160000.T124521.TXT";
        String archiveDir = environment.getArchiveDir() + "/sobis/2015";
        File archiveFile = preTestSetup(fileName, archiveDir, false);
        SourceFile retrievedArchiveFile = null;
        try {
            SourceFile sourceFile = new SobiFile(archiveFile);
            sourceFile.setArchived(true);
            sourceFileDao.updateSourceFile(sourceFile);
            retrievedArchiveFile = sourceFileDao.getSourceFile(fileName);
            assertEquals("Sobi File Insert Retrieval",
                    sourceFile.getPublishedDateTime(), retrievedArchiveFile.getPublishedDateTime());
        } finally {
            cleanUpFiles(archiveFile, retrievedArchiveFile.getFile());
        }
    }

    private void cleanUpFiles(File... files) {
        boolean allDeleted = Arrays.stream(files)
                .filter(File::exists)
                .map(FileUtils::deleteQuietly)
                .reduce(true, Boolean::logicalAnd);
        assertTrue("All Files non-existant / deleted", allDeleted);
    }
}
