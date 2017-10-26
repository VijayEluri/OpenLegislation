package gov.nysenate.openleg.processor.bill;

import gov.nysenate.openleg.model.base.Version;
import gov.nysenate.openleg.model.bill.*;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.entity.SessionMember;
import gov.nysenate.openleg.model.sourcefiles.sobi.SobiFragment;
import gov.nysenate.openleg.model.sourcefiles.sobi.SobiFragmentType;
import gov.nysenate.openleg.processor.base.AbstractDataProcessor;
import gov.nysenate.openleg.processor.base.ParseError;
import gov.nysenate.openleg.processor.sobi.SobiProcessor;
import gov.nysenate.openleg.util.XmlHelper;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
public class XmlSenFlVotProcessor extends AbstractDataProcessor implements SobiProcessor {
    private static final Logger logger = LoggerFactory.getLogger(XmlSenFlVotProcessor.class);

    /** Date format found in SobiBlock[V] vote memo blocks. e.g. 02/05/2013 */
    protected static final DateTimeFormatter voteDateFormat = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    @Autowired
    private XmlHelper xmlHelper;

    public XmlSenFlVotProcessor() {}

    @Override
    public void init() {
        initBase();
    }

    @Override
    public SobiFragmentType getSupportedType() {
        return SobiFragmentType.SENFLVOTE;
    }

    @Override
    public void process(SobiFragment sobiFragment) {
        logger.info("Processing SenFlVot...");
        logger.info("Processing " + sobiFragment.getFragmentId() + " (xml file).");
        try {
            LocalDateTime date = sobiFragment.getPublishedDateTime();
            final Document doc = xmlHelper.parse(sobiFragment.getText());
            final Node senFloorVote = xmlHelper.getNode("senfloorvote", doc);
            //File Print number
            final Integer sessyr = xmlHelper.getInteger("@sessyr", senFloorVote);
            final Integer seqno = xmlHelper.getInteger("@bill_seqno", senFloorVote);
            String printNo = xmlHelper.getString("@no", senFloorVote).trim();
            String version = Character.isLetter(printNo.charAt(printNo.length() - 1)) ? String.valueOf( printNo.charAt(printNo.length() - 1) ): "";
            if ( !version.equals("") ) { //If this isn't the default version
                printNo = printNo.substring(0, printNo.length()-1);
            }
            final String action = xmlHelper.getString("@action", senFloorVote).trim();
            final String dateofvote = xmlHelper.getString("@dateofvote", senFloorVote).trim();

            Bill baseBill = getOrCreateBaseBill(sobiFragment.getPublishedDateTime(), new BillId(printNo, sessyr, version), sobiFragment);
            BillAmendment billAmendment;
            if (!baseBill.hasAmendment( Version.of(version) )) {
                billAmendment = new BillAmendment(baseBill.getBaseBillId(), Version.of(version));
                baseBill.addAmendment(billAmendment);
            }
            else {
                billAmendment = baseBill.getAmendment(Version.of(version));
            }

            LocalDate voteDate;
            BillVote vote;
            BillId billId = billAmendment.getBillId();
            try {
                voteDate = LocalDate.from(voteDateFormat.parse(dateofvote));
                vote = new BillVote(billId, voteDate, BillVoteType.FLOOR, seqno);
                vote.setModifiedDateTime(date);
                vote.setPublishedDateTime(date);

                if (action.equals("remove")) {
                    removeCase(billAmendment, vote);
                    return;
                }
            }
            catch (DateTimeParseException ex) {
                throw new ParseError("voteDateFormat not matched: " + ex);
            }

            //loop through xml vote list
            NodeList memberVotes = doc.getElementsByTagName("member");
            for (int index = 0; index < memberVotes.getLength(); index++) {
                Node member = memberVotes.item(index);
                final String howMemberVoted = xmlHelper.getString("vote",member);
                final String shortName = xmlHelper.getString("name",member);

                BillVoteCode voteCode;
                try {
                    voteCode = BillVoteCode.getValue(howMemberVoted);
                }
                catch (IllegalArgumentException ex) {
                    throw new ParseError("No vote code mapping for " + howMemberVoted);
                }
                // Only senator votes are received. A valid member mapping is required.
                SessionMember voter = getMemberFromShortName(shortName, billId.getSession(), Chamber.SENATE);
                vote.addMemberVote(voteCode, voter);
            }
            billAmendment.updateVote(vote);
        }
        catch (IOException | SAXException | XPathExpressionException | NullPointerException e) {
            throw new ParseError("Error While Parsing XmlSenFlVotProcessor", e);
        }
    }

    @Override
    public void postProcess() {
        flushBillUpdates();
    }

    private void removeCase(BillAmendment billAmendment, BillVote vote) {
        billAmendment.updateVote(vote);
    }

}