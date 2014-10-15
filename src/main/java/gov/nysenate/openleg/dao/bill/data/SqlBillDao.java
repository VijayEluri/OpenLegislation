package gov.nysenate.openleg.dao.bill.data;

import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import gov.nysenate.openleg.dao.base.*;
import gov.nysenate.openleg.dao.common.BillVoteRowHandler;
import gov.nysenate.openleg.model.base.PublishStatus;
import gov.nysenate.openleg.model.base.SessionYear;
import gov.nysenate.openleg.model.base.Version;
import gov.nysenate.openleg.model.bill.*;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.entity.CommitteeVersionId;
import gov.nysenate.openleg.model.entity.Member;
import gov.nysenate.openleg.model.entity.MemberNotFoundEx;
import gov.nysenate.openleg.model.sobi.SobiFragment;
import gov.nysenate.openleg.service.bill.data.ApprovalDataService;
import gov.nysenate.openleg.service.bill.data.ApprovalNotFoundException;
import gov.nysenate.openleg.service.bill.data.VetoDataService;
import gov.nysenate.openleg.service.bill.data.VetoNotFoundException;
import gov.nysenate.openleg.service.entity.MemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static gov.nysenate.openleg.util.CollectionUtils.difference;
import static gov.nysenate.openleg.util.DateUtils.toDate;

@Repository
public class SqlBillDao extends SqlBaseDao implements BillDao
{
    private static final Logger logger = LoggerFactory.getLogger(SqlBillDao.class);

    @Autowired
    private MemberService memberService;
    @Autowired
    private VetoDataService vetoDataService;
    @Autowired
    private ApprovalDataService approvalDataService;

    /* --- Implemented Methods --- */

    /** {@inheritDoc} */
    @Override
    public Bill getBill(BillId billId) {
        logger.trace("Fetching Bill {} from database...", billId);
        final ImmutableParams baseParams = ImmutableParams.from(new MapSqlParameterSource()
            .addValue("printNo", billId.getBasePrintNo())
            .addValue("sessionYear", billId.getSession().getYear()));
        // Retrieve base Bill object
        Bill bill = getBaseBill(baseParams);
        // Fetch the amendments
        List<BillAmendment> billAmendments = getBillAmendments(baseParams);
        for (BillAmendment amendment : billAmendments) {
            final ImmutableParams amendParams = baseParams.add(
                new MapSqlParameterSource("version", amendment.getVersion().getValue()));
            // Fetch all the same as bill ids
            amendment.setSameAs(getSameAsBills(amendParams));
            // Get the cosponsors for the amendment
            amendment.setCoSponsors(getCoSponsors(amendParams));
            // Get the multi-sponsors for the amendment
            amendment.setMultiSponsors(getMultiSponsors(amendParams));
            // Get the votes
            amendment.setVotesMap(getBillVotes(amendParams));
        }
        // Set the amendments
        bill.addAmendments(billAmendments);
        // Set the publish status for each amendment
        bill.setPublishStatuses(getBillAmendPublishStatuses(baseParams));
        // Get the sponsor
        bill.setSponsor(getBillSponsor(baseParams));
        // Get the actions
        bill.setActions(getBillActions(baseParams));
        // Get the prev bill version ids
        bill.setPreviousVersions(getPrevVersions(baseParams));
        // Get the associated bill committees
        bill.setPastCommittees(getBillCommittees(baseParams));
        // Get the associated veto memos
        bill.setVetoMessages(getBillVetoMessages(bill.getBaseBillId()));
        // Get the approval message
        bill.setApprovalMessage(getBillApprovalMessage(bill.getBaseBillId()));
        // Bill has been fully constructed
        return bill;
    }

    /** {@inheritDoc} */
    @Override
    public BillInfo getBillInfo(BillId billId) throws DataAccessException {
        logger.trace("Fetching BillInfo {} from database...", billId);
        final ImmutableParams baseParams = ImmutableParams.from(new MapSqlParameterSource()
                .addValue("printNo", billId.getBasePrintNo())
                .addValue("sessionYear", billId.getSession().getYear()));
        // Retrieve base bill object
        Bill bill = getBaseBill(baseParams);
        BillInfo billInfo = new BillInfo();
        billInfo.setBillId(bill.getBaseBillId());
        billInfo.setActiveVersion(bill.getActiveVersion());
        billInfo.setTitle(bill.getTitle());
        billInfo.setSummary(bill.getSummary());
        billInfo.setStatus(bill.getStatus());
        // Get the sponsor
        billInfo.setSponsor(getBillSponsor(baseParams));
        return billInfo;
    }

    /**
     * {@inheritDoc}
     *
     * Updates information for an existing bill or creates new records if the bill is new.
     * Due to the normalized nature of the database it takes several queries to update all
     * the relevant pieces of data contained within the Bill object. The sobiFragment
     * reference is used to keep track of changes to the bill.
     */
    @Override
    public void updateBill(Bill bill, SobiFragment sobiFragment) {
        logger.trace("Updating Bill {} in database...", bill);
        // Update the bill record
        final ImmutableParams billParams = ImmutableParams.from(getBillParams(bill, sobiFragment));
        if (jdbcNamed.update(SqlBillQuery.UPDATE_BILL.getSql(schema()), billParams) == 0) {
            jdbcNamed.update(SqlBillQuery.INSERT_BILL.getSql(schema()), billParams);
        }
        // Update the bill amendments
        for (BillAmendment amendment : bill.getAmendmentList()) {
            final ImmutableParams amendParams = ImmutableParams.from(getBillAmendmentParams(amendment, sobiFragment));
            if (jdbcNamed.update(SqlBillQuery.UPDATE_BILL_AMENDMENT.getSql(schema()), amendParams) == 0) {
                jdbcNamed.update(SqlBillQuery.INSERT_BILL_AMENDMENT.getSql(schema()), amendParams);
            }
            // Update the same as bills
            updateBillSameAs(amendment, sobiFragment, amendParams);
            // Update the co-sponsors list
            updateBillCosponsor(amendment, sobiFragment, amendParams);
            // Update the multi-sponsors list
            updateBillMultiSponsor(amendment, sobiFragment, amendParams);
            // Update votes
            updateBillVotes(amendment, sobiFragment, amendParams);
        }
        // Update the publish statuses of the amendments
        updateBillAmendPublishStatus(bill, sobiFragment, billParams);
        // Update the sponsor
        updateBillSponsor(bill, sobiFragment, billParams);
        // Determine which actions need to be inserted/deleted. Individual actions are never updated.
        updateActions(bill, sobiFragment, billParams);
        // Determine if the previous versions have changed and insert accordingly.
        updatePreviousBillVersions(bill, sobiFragment, billParams);
        // Update associated committees
        updateBillCommittees(bill, sobiFragment, billParams);
        // Update veto messages
        updateVetoMessages(bill, sobiFragment);
        // Update approval message
        updateApprovalMessage(bill, sobiFragment);

    }

    /** {@inheritDoc} */
    @Override
    public List<BaseBillId> getBillIds(SessionYear sessionYear, LimitOffset limOff, SortOrder billIdSort) throws DataAccessException {
        ImmutableParams params = ImmutableParams.from(new MapSqlParameterSource("sessionYear", sessionYear.getYear()));
        OrderBy orderBy = new OrderBy("print_no", billIdSort, "session_year", billIdSort);
        return jdbcNamed.query(SqlBillQuery.SELECT_BILL_IDS_BY_SESSION.getSql(schema(), orderBy, limOff), params, (rs, row) ->
            new BaseBillId(rs.getString("print_no"), rs.getInt("session_year")));
    }

    /** {@inheritDoc} */
    @Override
    public int getBillCount() throws DataAccessException {
        return jdbc.queryForObject(SqlBillQuery.SELECT_COUNT_ALL_BILLS.getSql(schema()), (rs, row) -> rs.getInt("total"));
    }

    /** {@inheritDoc} */
    @Override
    public int getBillCount(SessionYear sessionYear) throws DataAccessException {
        ImmutableParams params = ImmutableParams.from(new MapSqlParameterSource("sessionYear", sessionYear.getYear()));
        return jdbcNamed.queryForObject(SqlBillQuery.SELECT_COUNT_ALL_BILLS_IN_SESSION.getSql(schema()), params,
            (rs,row) -> rs.getInt("total"));
    }

    /** {@inheritDoc} */
    @Override
    public void publishBill(Bill bill) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** {@inheritDoc} */
    @Override
    public void unPublishBill(Bill bill) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** --- Internal Methods --- */


    /**
     * Get the base bill instance for the base bill id in the params.
     */
    private Bill getBaseBill(ImmutableParams baseParams) {
        return jdbcNamed.queryForObject(SqlBillQuery.SELECT_BILL.getSql(schema()), baseParams, new BillRowMapper());
    }

    /**
     * Get a list of all the bill actions for the base bill id in the params.
     */
    private List<BillAction> getBillActions(ImmutableParams baseParams) {
        OrderBy orderBy = new OrderBy("sequence_no", SortOrder.ASC);
        LimitOffset limOff = LimitOffset.ALL;
        return jdbcNamed.query(SqlBillQuery.SELECT_BILL_ACTIONS.getSql(schema(), orderBy, limOff), baseParams, new BillActionRowMapper());
    }

    /**
     * Get previous session year bill ids for the base bill id in the params.
     */
    private Set<BillId> getPrevVersions(ImmutableParams baseParams) {
        return new HashSet<>(jdbcNamed.query(SqlBillQuery.SELECT_BILL_PREVIOUS_VERSIONS.getSql(schema()), baseParams,
                             new BillPreviousVersionRowMapper()));
    }

    /**
     * Get a set of the committee ids which represent the committees the bill was previously referred to.
     */
    private TreeSet<CommitteeVersionId> getBillCommittees(ImmutableParams baseParams){
        return new TreeSet<>(jdbcNamed.query(SqlBillQuery.SELECT_BILL_COMMITTEES.getSql(schema()), baseParams, new BillCommitteeRowMapper()));
    }

    /**
     * Get the same as bill ids for the bill id in the params.
     */
    private Set<BillId> getSameAsBills(ImmutableParams amendParams) {
        return new HashSet<>(jdbcNamed.query(SqlBillQuery.SELECT_BILL_SAME_AS.getSql(schema()), amendParams, new BillSameAsRowMapper()));
    }

    /**
     *
     * Get the bill sponsor for the bill id in the params. Return null if the sponsor has not been set yet.
     */
    private BillSponsor getBillSponsor(ImmutableParams baseParams) {
        try {
            return jdbcNamed.queryForObject(
                SqlBillQuery.SELECT_BILL_SPONSOR.getSql(schema()), baseParams, new BillSponsorRowMapper(memberService));
        }
        catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * Fetch the collection of bill amendment references for the base bill id in the params.
     */
    private List<BillAmendment> getBillAmendments(ImmutableParams baseParams) {
        return jdbcNamed.query(SqlBillQuery.SELECT_BILL_AMENDMENTS.getSql(schema()), baseParams, new BillAmendmentRowMapper());
    }

    /**
     * Get a map of the publish statuses for each amendment version.
     */
    private TreeMap<Version, PublishStatus> getBillAmendPublishStatuses(ImmutableParams baseParams) {
        BillAmendPublishStatusHandler handler = new BillAmendPublishStatusHandler();
        jdbcNamed.query(SqlBillQuery.SELECT_BILL_AMEND_PUBLISH_STATUSES.getSql(schema()), baseParams, handler);
        return handler.getPublishStatusMap();
    }

    /**
     * Get the co sponsors listing for the bill id in the params.
     */
    private List<Member> getCoSponsors(ImmutableParams amendParams) {
        return jdbcNamed.query(SqlBillQuery.SELECT_BILL_COSPONSORS.getSql(schema()), amendParams, new BillMemberRowMapper(memberService));
    }

    /**
     * Get the multi sponsors listing for the bill id in the params.
     */
    private List<Member> getMultiSponsors(ImmutableParams amendParams) {
        return jdbcNamed.query(SqlBillQuery.SELECT_BILL_MULTISPONSORS.getSql(schema()), amendParams, new BillMemberRowMapper(memberService));
    }

    /**
     * Get the votes for the bill id in the params.
     */
    private List<BillVote> getBillVotes(ImmutableParams baseParams) {
        BillVoteRowHandler voteHandler = new BillVoteRowHandler(memberService);
        jdbcNamed.query(SqlBillQuery.SELECT_BILL_VOTES.getSql(schema()), baseParams, voteHandler);
        return voteHandler.getBillVotes();
    }

    /**
     * Get veto memos for the bill
     */
    private Map<VetoId,VetoMessage> getBillVetoMessages(BaseBillId baseBillId) {
        try {
            return vetoDataService.getBillVetoes(baseBillId);
        }
        catch (VetoNotFoundException ex) {
            return new HashMap<>();
        }
    }

    private ApprovalMessage getBillApprovalMessage(BaseBillId baseBillId){
        try{
            return approvalDataService.getApprovalMessage(baseBillId);
        }
        catch(ApprovalNotFoundException ex){
            return null;
        }
    }

    /**
     * Updates the bill's same as set.
     */
    private void updateBillSameAs(BillAmendment amendment, SobiFragment sobiFragment, ImmutableParams amendParams) {
        Set<BillId> existingSameAs = getSameAsBills(amendParams);
        if (!existingSameAs.equals(amendment.getSameAs())) {
            Set<BillId> newSameAs = new HashSet<>(amendment.getSameAs());
            newSameAs.removeAll(existingSameAs);             // New same as bill ids to insert
            existingSameAs.removeAll(amendment.getSameAs()); // Old same as bill ids to delete
            existingSameAs.forEach(billId -> {
                ImmutableParams sameAsParams = ImmutableParams.from(getBillSameAsParams(amendment, billId, sobiFragment));
                jdbcNamed.update(SqlBillQuery.DELETE_SAME_AS.getSql(schema()), sameAsParams);
            });
            newSameAs.forEach(billId -> {
                ImmutableParams sameAsParams = ImmutableParams.from(getBillSameAsParams(amendment, billId, sobiFragment));
                jdbcNamed.update(SqlBillQuery.INSERT_BILL_SAME_AS.getSql(schema()), sameAsParams);
            });
        }
    }

    /**
     * Updates the bill's action list into the database.
     */
    private void updateActions(Bill bill, SobiFragment sobiFragment, ImmutableParams billParams) {
        List<BillAction> existingBillActions = getBillActions(billParams);
        List<BillAction> newBillActions = new ArrayList<>(bill.getActions());
        newBillActions.removeAll(existingBillActions);    // New actions to insert
        existingBillActions.removeAll(bill.getActions()); // Old actions to delete
        // Delete actions that are not in the updated list
        for (BillAction action : existingBillActions) {
            MapSqlParameterSource actionParams = getBillActionParams(action, sobiFragment);
            jdbcNamed.update(SqlBillQuery.DELETE_BILL_ACTION.getSql(schema()), actionParams);
        }
        // Insert all new actions
        for (BillAction action : newBillActions) {
            MapSqlParameterSource actionParams = getBillActionParams(action, sobiFragment);
            jdbcNamed.update(SqlBillQuery.INSERT_BILL_ACTION.getSql(schema()), actionParams);
        }
    }

    /**
     * Update the bill's previous version set.
     */
    private void updatePreviousBillVersions(Bill bill, SobiFragment sobiFragment, ImmutableParams billParams) {
        Set<BillId> existingPrevBills = getPrevVersions(billParams);
        if (!existingPrevBills.equals(bill.getPreviousVersions())) {
            Set<BillId> newPrevBills = new HashSet<>(bill.getPreviousVersions());
            newPrevBills.removeAll(existingPrevBills);               // New prev bill ids to insert
            existingPrevBills.removeAll(bill.getPreviousVersions()); // Old prev bill ids to delete
            existingPrevBills.forEach(billId -> {
                ImmutableParams prevParams = ImmutableParams.from(getBillPrevVersionParams(bill, billId, sobiFragment));
                jdbcNamed.update(SqlBillQuery.DELETE_BILL_PREVIOUS_VERSIONS.getSql(schema()), prevParams);
            });
            newPrevBills.forEach(billId -> {
                ImmutableParams prevParams = ImmutableParams.from(getBillPrevVersionParams(bill, billId, sobiFragment));
                jdbcNamed.update(SqlBillQuery.INSERT_BILL_PREVIOUS_VERSION.getSql(schema()), prevParams);
            });
        }
    }

    /**
     * Update the bill's previous committee set.
     */
    private void updateBillCommittees(Bill bill, SobiFragment sobiFragment, ImmutableParams billParams) {
        Set<CommitteeVersionId> existingComms = getBillCommittees(billParams);
        if (!existingComms.equals(bill.getPastCommittees())) {
            Set<CommitteeVersionId> newComms = new HashSet<>(bill.getPastCommittees());
            newComms.removeAll(existingComms);                 // New committees to insert
            existingComms.removeAll(bill.getPastCommittees()); // Old committees to delete
            existingComms.forEach(cvid -> {
                ImmutableParams commParams = ImmutableParams.from(getBillCommitteeParams(bill, cvid, sobiFragment));
                jdbcNamed.update(SqlBillQuery.DELETE_BILL_COMMITTEE.getSql(schema()), commParams);
            });
            newComms.forEach(cvid -> {
                ImmutableParams commParams = ImmutableParams.from(getBillCommitteeParams(bill, cvid, sobiFragment));
                jdbcNamed.update(SqlBillQuery.INSERT_BILL_COMMITTEE.getSql(schema()), commParams);
            });
        }
    }

    /**
     * Update any veto messages through the veto data service
     */
    private void updateVetoMessages(Bill bill, SobiFragment sobiFragment){
        vetoDataService.deleteBillVetoes(bill.getBaseBillId());
        for(VetoMessage vetoMessage : bill.getVetoMessages().values()){
            vetoDataService.updateVetoMessage(vetoMessage, sobiFragment);
        }
    }

    private void updateApprovalMessage(Bill bill, SobiFragment sobiFragment){
        approvalDataService.deleteApprovalMessage(bill.getBaseBillId());
        if(bill.getApprovalMessage() != null){
            approvalDataService.updateApprovalMessage(bill.getApprovalMessage(), sobiFragment);
        }
    }

    /**
     * Update the bill's sponsor information.
     */
    private void updateBillSponsor(Bill bill, SobiFragment sobiFragment, ImmutableParams billParams) {
        if (bill.getSponsor() != null) {
            MapSqlParameterSource params = getBillSponsorParams(bill, sobiFragment);
            if (jdbcNamed.update(SqlBillQuery.UPDATE_BILL_SPONSOR.getSql(schema()), params) == 0) {
                jdbcNamed.update(SqlBillQuery.INSERT_BILL_SPONSOR.getSql(schema()), params);
            }
        }
        else {
            jdbcNamed.update(SqlBillQuery.DELETE_BILL_SPONSOR.getSql(schema()), billParams);
        }
    }

    /**
     * Update the bill's amendment publish statuses.
     */
    private void updateBillAmendPublishStatus(Bill bill, SobiFragment sobiFragment, ImmutableParams billParams) {
        Map<Version, PublishStatus> existingPubStatus = getBillAmendPublishStatuses(billParams);
        Map<Version, PublishStatus> newPubStatus = bill.getAmendPublishStatusMap();
        MapDifference<Version, PublishStatus> diff = Maps.difference(existingPubStatus, newPubStatus);
        // Old entries that do not show up in the new one should be marked as unpublished
        diff.entriesOnlyOnLeft().forEach((version,pubStatus) -> {
            if (!pubStatus.isOverride() && pubStatus.isPublished()) {
                LocalDateTime dateTime = (sobiFragment != null) ? sobiFragment.getPublishedDateTime()
                                                                : LocalDateTime.now();
                PublishStatus unPubStatus = new PublishStatus(false, dateTime, false, "No longer referenced");
                MapSqlParameterSource params = getBillPublishStatusParams(bill, version, unPubStatus, sobiFragment);
                jdbcNamed.update(SqlBillQuery.UPDATE_BILL_AMEND_PUBLISH_STATUS.getSql(schema()), params);
            }
        });
        // Update changed publish statuses if the existing is not an override
        diff.entriesDiffering().forEach((version,pubStatus) -> {
            if (!pubStatus.leftValue().isOverride()) {
                MapSqlParameterSource params = getBillPublishStatusParams(bill, version, pubStatus.rightValue(), sobiFragment);
                jdbcNamed.update(SqlBillQuery.UPDATE_BILL_AMEND_PUBLISH_STATUS.getSql(schema()), params);
            }
        });
        // Insert new publish statuses
        diff.entriesOnlyOnRight().forEach((version,pubStatus) -> {
            MapSqlParameterSource params = getBillPublishStatusParams(bill, version, pubStatus, sobiFragment);
            jdbcNamed.update(SqlBillQuery.INSERT_BILL_AMEND_PUBLISH_STATUS.getSql(schema()), params);
        });
    }

    /**
     * Update the bill's co sponsor list by deleting, inserting, and updating as needed.
     */
    private void updateBillCosponsor(BillAmendment billAmendment, SobiFragment sobiFragment, ImmutableParams amendParams) {
        List<Member> existingCoSponsors = getCoSponsors(amendParams);
        if (!existingCoSponsors.equals(billAmendment.getCoSponsors())) {
            MapDifference<Member, Integer> diff = difference(existingCoSponsors, billAmendment.getCoSponsors(), 1);
            // Delete old cosponsors
            diff.entriesOnlyOnLeft().forEach((member,ordinal) -> {
                ImmutableParams cspParams = amendParams.add(new MapSqlParameterSource("sessionMemberId", member.getSessionMemberId()));
                jdbcNamed.update(SqlBillQuery.DELETE_BILL_COSPONSOR.getSql(schema()), cspParams);
            });
            // Update re-ordered cosponsors
            diff.entriesDiffering().forEach((member,ordinal) -> {
                ImmutableParams cspParams = ImmutableParams.from(
                    getCoMultiSponsorParams(billAmendment, member, ordinal.rightValue(),sobiFragment));
                jdbcNamed.update(SqlBillQuery.UPDATE_BILL_COSPONSOR.getSql(schema()), cspParams);
            });
            // Insert new cosponsors
            diff.entriesOnlyOnRight().forEach((member,ordinal) -> {
                ImmutableParams cspParams = ImmutableParams.from(
                    getCoMultiSponsorParams(billAmendment, member, ordinal,sobiFragment));
                jdbcNamed.update(SqlBillQuery.INSERT_BILL_COSPONSOR.getSql(schema()), cspParams);
            });
        }
    }

    /**
     * Update the bill's multi-sponsor list by deleting, inserting, and updating as needed.
     */
    private void updateBillMultiSponsor(BillAmendment billAmendment, SobiFragment sobiFragment, ImmutableParams amendParams) {
        List<Member> existingMultiSponsors = getMultiSponsors(amendParams);
        if (!existingMultiSponsors.equals(billAmendment.getMultiSponsors())) {
            MapDifference<Member, Integer> diff = difference(existingMultiSponsors, billAmendment.getMultiSponsors(), 1);
            // Delete old multisponsors
            diff.entriesOnlyOnLeft().forEach((member,ordinal) -> {
                ImmutableParams mspParams = amendParams.add(new MapSqlParameterSource("sessionMemberId", member.getSessionMemberId()));
                jdbcNamed.update(SqlBillQuery.DELETE_BILL_MULTISPONSOR.getSql(schema()), mspParams);
            });
            // Update re-ordered multisponsors
            diff.entriesDiffering().forEach((member,ordinal) -> {
                ImmutableParams mspParams = ImmutableParams.from(
                    getCoMultiSponsorParams(billAmendment, member, ordinal.rightValue(),sobiFragment));
                jdbcNamed.update(SqlBillQuery.UPDATE_BILL_MULTISPONSOR.getSql(schema()), mspParams);
            });
            // Insert new multisponsors
            diff.entriesOnlyOnRight().forEach((member,ordinal) -> {
                ImmutableParams mspParams = ImmutableParams.from(
                    getCoMultiSponsorParams(billAmendment, member, ordinal,sobiFragment));
                jdbcNamed.update(SqlBillQuery.INSERT_BILL_MULTISPONSOR.getSql(schema()), mspParams);
            });
        }
    }

    /**
     * Update the bill amendment's list of votes.
     */
    private void updateBillVotes(BillAmendment billAmendment, SobiFragment sobiFragment, ImmutableParams amendParams) {
        List<BillVote> existingBillVotes = getBillVotes(amendParams);
        List<BillVote> newBillVotes = new ArrayList<>(billAmendment.getVotesList());
        newBillVotes.removeAll(existingBillVotes);                 // New votes to insert/update
        existingBillVotes.removeAll(billAmendment.getVotesList()); // Old votes to remove
        // Delete all votes that have been updated
        for (BillVote billVote : existingBillVotes) {
            MapSqlParameterSource voteInfoParams = getBillVoteInfoParams(billAmendment, billVote, sobiFragment);
            jdbcNamed.update(SqlBillQuery.DELETE_BILL_VOTES_INFO.getSql(schema()), voteInfoParams);
        }
        // Insert the new/updated votes
        for (BillVote billVote : newBillVotes) {
            MapSqlParameterSource voteParams = getBillVoteInfoParams(billAmendment, billVote, sobiFragment);
            jdbcNamed.update(SqlBillQuery.INSERT_BILL_VOTES_INFO.getSql(schema()), voteParams);
            for (BillVoteCode voteCode : billVote.getMemberVotes().keySet()) {
                voteParams.addValue("voteCode", voteCode.name().toLowerCase());
                for (Member member : billVote.getMembersByVote(voteCode)) {
                    voteParams.addValue("sessionMemberId", member.getSessionMemberId());
                    voteParams.addValue("memberShortName", member.getLbdcShortName());
                    jdbcNamed.update(SqlBillQuery.INSERT_BILL_VOTES_ROLL.getSql(schema()), voteParams);
                }
            }
        }
    }

    /** --- Helper Classes --- */

    private static class BillRowMapper implements RowMapper<Bill>
    {
        @Override
        public Bill mapRow(ResultSet rs, int rowNum) throws SQLException {
            Bill bill = new Bill(new BaseBillId(rs.getString("print_no"), rs.getInt("session_year")));
            bill.setTitle(rs.getString("title"));
            bill.setSummary(rs.getString("summary"));
            bill.setActiveVersion(Version.of(rs.getString("active_version")));
            if (rs.getString("program_info") != null) {
                bill.setProgramInfo(new ProgramInfo(rs.getString("program_info"), rs.getInt("program_info_num")));
            }
            bill.setYear(rs.getInt("active_year"));
            if (rs.getString("status") != null) {
                bill.setStatus(new BillStatus(BillStatusType.valueOf(rs.getString("status")),
                               rs.getDate("status_date").toLocalDate()));
            }
            setModPubDatesFromResultSet(bill, rs);
            return bill;
        }
    }

    private static class BillAmendmentRowMapper implements RowMapper<BillAmendment>
    {
        @Override
        public BillAmendment mapRow(ResultSet rs, int rowNum) throws SQLException {
            BaseBillId baseBillId = new BaseBillId(rs.getString("bill_print_no"), rs.getInt("bill_session_year"));
            BillAmendment amend = new BillAmendment(baseBillId, Version.of(rs.getString("version")));
            amend.setMemo(rs.getString("sponsor_memo"));
            amend.setActClause(rs.getString("act_clause"));
            amend.setFullText(rs.getString("full_text"));
            amend.setStricken(rs.getBoolean("stricken"));
            amend.setUniBill(rs.getBoolean("uni_bill"));
            amend.setLawSection(rs.getString("law_section"));
            amend.setLaw(rs.getString("law_code"));
            String currentCommitteeName = rs.getString("current_committee_name");
            if (currentCommitteeName != null) {
                amend.setCurrentCommittee(
                    new CommitteeVersionId(
                        amend.getBillId().getChamber(), rs.getString("current_committee_name"),
                        amend.getSession(), getLocalDateFromRs(rs, "current_committee_action")
                    )
                );
            }
            else {
                amend.setCurrentCommittee(null);
            }
            return amend;
        }
    }

    private static class BillAmendPublishStatusHandler implements RowCallbackHandler
    {
        TreeMap<Version, PublishStatus> publishStatusMap = new TreeMap<>();

        @Override
        public void processRow(ResultSet rs) throws SQLException {
            PublishStatus pubStatus = new PublishStatus(
                rs.getBoolean("published"), getLocalDateTimeFromRs(rs, "effect_date_time"),
                rs.getBoolean("override"), rs.getString("notes"));
            publishStatusMap.put(Version.of(rs.getString("bill_amend_version")), pubStatus);
        }

        public TreeMap<Version, PublishStatus> getPublishStatusMap() {
            return publishStatusMap;
        }
    }

    private static class BillActionRowMapper implements RowMapper<BillAction>
    {
        @Override
        public BillAction mapRow(ResultSet rs, int rowNum) throws SQLException {
            BillAction billAction = new BillAction();
            billAction.setBillId(new BillId(rs.getString("bill_print_no"), rs.getInt("bill_session_year"),
                    rs.getString("bill_amend_version")));
            billAction.setChamber(Chamber.valueOf(rs.getString("chamber").toUpperCase()));
            billAction.setSequenceNo(rs.getInt("sequence_no"));
            billAction.setDate(getLocalDateFromRs(rs, "effect_date"));
            billAction.setText(rs.getString("text"));
            return billAction;
        }
    }

    private static class BillSameAsRowMapper implements RowMapper<BillId>
    {
        @Override
        public BillId mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BillId(rs.getString("same_as_bill_print_no"), rs.getInt("same_as_session_year"),
                              rs.getString("same_as_amend_version"));
        }
    }

    private static class BillPreviousVersionRowMapper implements RowMapper<BillId>
    {
        @Override
        public BillId mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BillId(rs.getString("prev_bill_print_no"), rs.getInt("prev_bill_session_year"),
                              rs.getString("prev_amend_version"));
        }
    }

    private static class BillSponsorRowMapper implements RowMapper<BillSponsor>
    {
        MemberService memberService;

        private BillSponsorRowMapper(MemberService memberService) {
            this.memberService = memberService;
        }

        @Override
        public BillSponsor mapRow(ResultSet rs, int rowNum) throws SQLException {
            BillSponsor sponsor = new BillSponsor();
            int sessionMemberId = rs.getInt("session_member_id");
            SessionYear sessionYear = getSessionYearFromRs(rs, "bill_session_year");
            sponsor.setBudgetBill(rs.getBoolean("budget_bill"));
            sponsor.setRulesSponsor(rs.getBoolean("rules_sponsor"));
            if (sessionMemberId > 0) {
                try {
                    sponsor.setMember(memberService.getMemberBySessionId(sessionMemberId));
                }
                catch (MemberNotFoundEx memberNotFoundEx) {
                    logger.warn("Bill referenced a sponsor that does not exist. {}", memberNotFoundEx.getMessage());
                }
            }
            return sponsor;
        }
    }

    private static class BillMemberRowMapper implements RowMapper<Member>
    {
        MemberService memberService;

        private BillMemberRowMapper(MemberService memberService) {
            this.memberService = memberService;
        }

        @Override
        public Member mapRow(ResultSet rs, int rowNum) throws SQLException {
            int sessionMemberId = rs.getInt("session_member_id");
            SessionYear sessionYear = getSessionYearFromRs(rs, "bill_session_year");
            try {
                return memberService.getMemberBySessionId(sessionMemberId);
            }
            catch (MemberNotFoundEx memberNotFoundEx) {
                logger.warn("Bill referenced a member that does not exist: {}", memberNotFoundEx.getMessage());
            }
            return null;
        }
    }

    private static class BillCommitteeRowMapper implements RowMapper<CommitteeVersionId>
    {
        @Override
        public CommitteeVersionId mapRow(ResultSet rs, int rowNum) throws SQLException {
            String committeeName = rs.getString("committee_name");
            Chamber committeeChamber = Chamber.getValue(rs.getString("committee_chamber"));
            SessionYear session = getSessionYearFromRs(rs, "bill_session_year");
            LocalDate actionDate = getLocalDateFromRs(rs, "action_date");
            return new CommitteeVersionId(committeeChamber, committeeName, session, actionDate);
        }
    }

    /** --- Param Source Methods --- */

    /**
     * Returns a MapSqlParameterSource with columns mapped to Bill values for use in update/insert queries on
     * the bill table.
     */
    private static MapSqlParameterSource getBillParams(Bill bill, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addBillIdParams(bill, params);
        params.addValue("title", bill.getTitle())
              .addValue("summary", bill.getSummary())
              .addValue("activeVersion", bill.getActiveVersion().getValue())
              .addValue("activeYear", bill.getYear())
              .addValue("programInfo", bill.getProgramInfo()!=null ? bill.getProgramInfo().getInfo() : null)
              .addValue("programInfoNum", bill.getProgramInfo()!=null ? bill.getProgramInfo().getNumber() : null)
              .addValue("status", bill.getStatus() != null ? bill.getStatus().getStatusType().name() : null)
              .addValue("statusDate", bill.getStatus() != null ? toDate(bill.getStatus().getActionDate()) : null);
        addModPubDateParams(bill.getModifiedDateTime(), bill.getPublishedDateTime(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    /**
     * Returns a MapSqlParameterSource with columns mapped to BillAmendment values for use in update/insert
     * queries on the bill amendment table.
     */
    private static MapSqlParameterSource getBillAmendmentParams(BillAmendment amendment, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addBillIdParams(amendment, params);
        params.addValue("sponsorMemo", amendment.getMemo())
              .addValue("actClause", amendment.getActClause())
              .addValue("fullText", amendment.getFullText())
              .addValue("stricken", amendment.isStricken())
              .addValue("lawSection", amendment.getLawSection())
              .addValue("lawCode", amendment.getLaw())
              .addValue("currentCommitteeName", amendment.getCurrentCommittee() != null ?
                      amendment.getCurrentCommittee().getName() : null)
              .addValue("currentCommitteeAction", amendment.getCurrentCommittee() != null ?
                      toDate(amendment.getCurrentCommittee().getReferenceDate()) : null)
              .addValue("uniBill", amendment.isUniBill());
        addLastFragmentParam(fragment, params);
        return params;
    }

    private static MapSqlParameterSource getBillPublishStatusParams(Bill bill, Version version, PublishStatus pubStatus,
                                                                    SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addBillIdParams(bill, params);
        params.addValue("version", version.getValue());
        params.addValue("published", pubStatus.isPublished());
        params.addValue("effectDateTime", toDate(pubStatus.getEffectDateTime()));
        params.addValue("override", pubStatus.isOverride());
        params.addValue("notes", pubStatus.getNotes());
        addLastFragmentParam(fragment, params);
        return params;
    }

    /**
     * Returns a MapSqlParameterSource with columns mapped to BillAction for use in inserting records
     * into the bill action table.
     */
    private static MapSqlParameterSource getBillActionParams(BillAction billAction, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("printNo", billAction.getBillId().getBasePrintNo())
              .addValue("sessionYear", billAction.getBillId().getSession().getYear())
              .addValue("chamber", billAction.getChamber().toString().toLowerCase())
              .addValue("version", billAction.getBillId().getVersion().getValue())
              .addValue("effectDate", toDate(billAction.getDate()))
              .addValue("text", billAction.getText())
              .addValue("sequenceNo", billAction.getSequenceNo());
        addLastFragmentParam(fragment, params);
        return params;
    }

    private static MapSqlParameterSource getBillSameAsParams(BillAmendment billAmendment, BillId sameAs, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addBillIdParams(billAmendment, params);
        params.addValue("sameAsPrintNo", sameAs.getBasePrintNo())
              .addValue("sameAsSessionYear", sameAs.getSession().getYear())
              .addValue("sameAsVersion", sameAs.getVersion().getValue());
        addLastFragmentParam(fragment, params);
        return params;
    }

    private static MapSqlParameterSource getBillPrevVersionParams(Bill bill, BillId prevVersion, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addBillIdParams(bill, params);
        params.addValue("prevPrintNo", prevVersion.getBasePrintNo())
              .addValue("prevSessionYear", prevVersion.getSession().getYear())
              .addValue("prevVersion", prevVersion.getVersion().getValue());
        addLastFragmentParam(fragment, params);
        return params;
    }

    private static MapSqlParameterSource getBillSponsorParams(Bill bill, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        BillSponsor billSponsor = bill.getSponsor();
        boolean hasMember = billSponsor != null && billSponsor.hasMember();
        addBillIdParams(bill, params);
        params.addValue("sessionMemberId", (hasMember) ? billSponsor.getMember().getSessionMemberId() : null)
              .addValue("budgetBill", (billSponsor != null && billSponsor.isBudgetBill()))
              .addValue("rulesSponsor", (billSponsor != null && billSponsor.isRulesSponsor()));
        addLastFragmentParam(fragment, params);
        return params;
    }

    private static MapSqlParameterSource getCoMultiSponsorParams(BillAmendment billAmendment, Member member,
                                                                 int sequenceNo, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addBillIdParams(billAmendment, params);
        params.addValue("sessionMemberId", member.getSessionMemberId())
              .addValue("sequenceNo", sequenceNo);
        addLastFragmentParam(fragment, params);
        return params;
    }

    private static MapSqlParameterSource getBillVoteInfoParams(BillAmendment billAmendment, BillVote billVote,
                                                               SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addBillIdParams(billAmendment, params);
        params.addValue("voteDate", toDate(billVote.getVoteDate()))
              .addValue("voteType", billVote.getVoteType().name().toLowerCase())
              .addValue("sequenceNo", billVote.getSequenceNo())
              .addValue("committeeName", (billVote.getCommitteeId() != null)
                                         ? billVote.getCommitteeId().getName() : null)
              .addValue("committeeChamber", (billVote.getCommitteeId() != null)
                                            ? billVote.getCommitteeId().getChamber().asSqlEnum() : null);
        addModPubDateParams(billVote.getModifiedDateTime(), billVote.getPublishedDateTime(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    private static MapSqlParameterSource getBillCommitteeParams(Bill bill, CommitteeVersionId committee,
                                                                SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addBillIdParams(bill, params);
        params.addValue("committeeName", committee.getName())
              .addValue("committeeChamber", committee.getChamber().asSqlEnum())
              .addValue("actionDate", toDate(committee.getReferenceDate()));
        addLastFragmentParam(fragment, params);
        return params;
    }

    /**
     * Applies columns that identify the base bill.
     */
    private static void addBillIdParams(Bill bill, MapSqlParameterSource params) {
        params.addValue("printNo", bill.getBasePrintNo())
              .addValue("sessionYear", bill.getSession().getYear());
    }

    /**
     * Adds columns that identify the bill amendment.
     */
    private static void addBillIdParams(BillAmendment billAmendment, MapSqlParameterSource params) {
        params.addValue("printNo", billAmendment.getBasePrintNo())
              .addValue("sessionYear", billAmendment.getSession().getYear())
              .addValue("version", billAmendment.getVersion().getValue());
    }
}