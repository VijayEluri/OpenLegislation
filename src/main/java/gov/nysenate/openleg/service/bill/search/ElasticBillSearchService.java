package gov.nysenate.openleg.service.bill.search;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.bill.search.ElasticBillSearchDao;
import gov.nysenate.openleg.model.base.Environment;
import gov.nysenate.openleg.model.bill.BaseBillId;
import gov.nysenate.openleg.model.bill.Bill;
import gov.nysenate.openleg.service.base.SearchException;
import gov.nysenate.openleg.service.base.SearchIndexFlushEvent;
import gov.nysenate.openleg.service.base.SearchResults;
import gov.nysenate.openleg.service.bill.data.BillUpdateEvent;
import gov.nysenate.openleg.service.bill.data.BulkBillUpdateEvent;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.search.SearchParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ElasticBillSearchService implements BillSearchService
{
    private static final Logger logger = LoggerFactory.getLogger(ElasticBillSearchService.class);

    @Autowired
    protected Environment env;

    @Autowired
    protected EventBus eventBus;

    @Autowired
    protected ElasticBillSearchDao billSearchDao;

    @PostConstruct
    protected void init() {
        eventBus.register(this);
    }

    /** {@inheritDoc} */
    @Override
    public SearchResults<BaseBillId> searchBills(String query, String sort, LimitOffset limOff) throws SearchException {
        if (limOff == null) {
            limOff = LimitOffset.TEN;
        }
        try {
            return billSearchDao.searchBills(query, sort, limOff);
        }
        catch (SearchParseException ex) {
            throw new SearchException("There was a problem parsing the supplied query string.", ex);
        }
        catch (ElasticsearchException ex) {
            throw new SearchException("Unexpected search exception!", ex);
        }
    }

    /** {@inheritDoc} */
    @Subscribe
    @Override
    public void handleBillUpdate(BillUpdateEvent billUpdateEvent) {
        if (billUpdateEvent.getBill() != null) {
            updateBillIndex(billUpdateEvent.getBill());
        }
    }

    /** {@inheritDoc} */
    @Subscribe
    @Override
    public void handeBulkBillUpdate(BulkBillUpdateEvent bulkBillUpdateEvent) {
        if (bulkBillUpdateEvent.getBills() != null) {
            updateBillIndices(bulkBillUpdateEvent.getBills());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void updateBillIndex(Bill bill) {
        if (env.isElasticIndexing()) {
            if (isBillIndexable(bill)) {
                logger.info("Indexing bill {} into elastic search.", bill.getBaseBillId());
                billSearchDao.updateBillIndex(bill);
            }
            else if (bill != null) {
                logger.info("Deleting {} from index.", bill.getBaseBillId());
                billSearchDao.deleteBillFromIndex(bill.getBaseBillId());
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void updateBillIndices(Collection<Bill> bills) {
        if (env.isElasticIndexing()) {
            List<Bill> indexableBills = bills.stream()
                .filter(b -> isBillIndexable(b))
                .collect(Collectors.toList());
            logger.info("Indexing {} bills into elastic search.", indexableBills.size());
            billSearchDao.updateBulkBillIndices(indexableBills);

            // Ensure any bills that currently don't meet the criteria are not in the index.
            if (indexableBills.size() != bills.size()) {
                bills.stream()
                    .filter(b -> !isBillIndexable(b) && b != null)
                    .forEach(b -> {
                        logger.info("Deleting {} from index.", b.getBaseBillId());
                        billSearchDao.deleteBillFromIndex(b.getBaseBillId());
                    });
            }
        }
    }

    /**
     * Returns true if the given bill meets the criteria for being indexed in the search layer.
     *
     * @param bill Bill
     * @return boolean
     */
    protected boolean isBillIndexable(Bill bill) {
        return bill != null && bill.isBaseVersionPublished();
    }
}