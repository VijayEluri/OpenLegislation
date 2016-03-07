package gov.nysenate.openleg.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.nysenate.openleg.model.spotcheck.SpotCheckRefType;
import gov.nysenate.openleg.model.spotcheck.senatesite.SenateSiteDumpFragment;
import gov.nysenate.openleg.model.spotcheck.senatesite.SenateSiteDumpId;
import gov.nysenate.openleg.model.spotcheck.senatesite.SenateSiteDumpRangeId;
import gov.nysenate.openleg.model.spotcheck.senatesite.SenateSiteDumpSessionId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Parses a {@link SenateSiteDumpFragment} from a json String and {@link SpotCheckRefType}.
 * The json string has the following mandatory strings: from, to, part, totalParts, session.
 */
@Service
public class SenateSiteDumpFragParser {

    @Autowired private ObjectMapper objectMapper;

    /**
     * <p>Parse a json string into a {@link SenateSiteDumpFragment}. Its SenateSiteDumpId implementation
     * depends on what information is included in the json. A json with valid values for <code>from</code>
     * and <code>to</code> will use a {@link SenateSiteDumpRangeId} while a json with a valid <code>session</code>
     * value will use {@link SenateSiteDumpSessionId}.</p>
     * <p>Throws <code>SenateSiteDumpFragParserException</code> if a required json value is missing.</p>
     * @param json The json string to parse.
     * @param refType The SpotCheckRefType of this json.
     * @return
     * @throws IOException
     * @see SenateSiteDumpId
     */
    public SenateSiteDumpFragment parseFragment(String json, SpotCheckRefType refType) throws IOException {
        JsonNode rootNode = objectMapper.readValue(json, JsonNode.class);
        LocalDateTime from = parseDateTimeFromNode(getRequiredNode(rootNode, "from"));
        LocalDateTime to = parseDateTimeFromNode(getRequiredNode(rootNode, "to"));
        int part = getRequiredNode(rootNode, "part").asInt();
        int totalParts = getRequiredNode(rootNode, "totalParts").asInt();

        JsonNode sessionNode = getRequiredNode(rootNode, "session");
        Optional<Integer> sessionYearOpt = sessionNode.isNull() ? Optional.empty() : Optional.of(sessionNode.asInt());

        SenateSiteDumpId dumpId = createDumpId(from, to, totalParts, sessionYearOpt, refType);
        return new SenateSiteDumpFragment(dumpId, part);
    }

    /**
     * Get a node from a rootNode and field name.
     * If the node does not exist, throw exception.
     */
    private JsonNode getRequiredNode(JsonNode rootNode, String fieldName) {
        if (rootNode.has(fieldName)) {
            return rootNode.get(fieldName);
        }
        else {
            throw SenateSiteDumpFragParserException.missingField(fieldName);
        }
    }

    /** Return a LocalDateTime parsed from a JsonNode if the node contains text. Otherwise return null. */
    private LocalDateTime parseDateTimeFromNode(JsonNode node) {
        if (node.asText().equals("")) {
            return null;
        }
        return LocalDateTime.parse(node.asText(), DateUtils.PUBLIC_WEBSITE_DUMP_DATETIME_FORMAT);
    }

    /**
     * Returns a specific <code>SenateSiteDumpId</code> implementation depending on data in the json.
     * @see #parseFragment(String, SpotCheckRefType)
     */
    private SenateSiteDumpId createDumpId(LocalDateTime from, LocalDateTime to, int totalParts,
                                          Optional<Integer> sessionYearOpt, SpotCheckRefType refType) {
        SenateSiteDumpId dumpId = null;
        if (to == null) {
            throw SenateSiteDumpFragParserException.nullField("to");
        }
        if (sessionYearOpt.isPresent()) {
            dumpId = new SenateSiteDumpSessionId(refType, totalParts, sessionYearOpt.get(), to);
        } else if (from != null) {
            dumpId = new SenateSiteDumpRangeId(refType, totalParts, from, to);
        } else {
            throw new SenateSiteDumpFragParserException(
                    "Invalid senate site dump fragment: both 'from' and 'session' fields are null");
        }
        return dumpId;
    }

    /** Exceptions */

    public static class SenateSiteDumpFragParserException extends RuntimeException {
        private static final long serialVersionUID = 5133300734005459879L;

        private SenateSiteDumpFragParserException(String message) {
            super(message);
        }

        public static SenateSiteDumpFragParserException missingField(String missingField) {
            return new SenateSiteDumpFragParserException(
                    "Required field: " + missingField + ", is missing from json dump.");
        }

        public static SenateSiteDumpFragParserException nullField(String field) {
            return new SenateSiteDumpFragParserException(
                    "Required field: " + field + " is null");
        }
    }
}
