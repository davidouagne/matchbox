package ch.ahdis.matchbox.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ch.ahdis.matchbox.engine.exception.MatchboxUnsupportedFhirVersionException;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_40_50;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_43_50;
import org.hl7.fhir.r5.model.CapabilityStatement;
import org.hl7.fhir.r5.model.TerminologyCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.hl7.fhir.r5.terminologies.utilities.TerminologyCache.SystemNameKeyGenerator.*;

/**
 * An interceptor that provides the terminology capabilities of the server when requested. Otherwise, HAPI sends the
 * capability statement again.
 *
 * @author Quentin Ligier
 **/
@Interceptor
public class TerminologyCapabilitiesInterceptor {
	private static final Logger log = LoggerFactory.getLogger(TerminologyCapabilitiesInterceptor.class);

	@Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
	public boolean customize(final RequestDetails theRequestDetails,
								 final ResponseDetails theResponseDetails) {
		if (theRequestDetails.getParameters().containsKey("mode") && "terminology".equals(theRequestDetails.getParameters().get("mode")[0])) {
			log.debug("Generating a TerminologyCapabilities");
			final var baseResource = theResponseDetails.getResponseResource();
			if (baseResource instanceof final CapabilityStatement csR5) {
				theResponseDetails.setResponseResource(this.getTerminologyCapabilities(csR5));
			} else if (baseResource instanceof final org.hl7.fhir.r4.model.CapabilityStatement csR4) {
				final var csR5 = (CapabilityStatement) VersionConvertorFactory_40_50.convertResource(csR4);
				theResponseDetails.setResponseResource(VersionConvertorFactory_40_50.convertResource(this.getTerminologyCapabilities(csR5)));
			} else if (baseResource instanceof final org.hl7.fhir.r4b.model.CapabilityStatement csR4B) {
				final var csR5 = (CapabilityStatement) VersionConvertorFactory_43_50.convertResource(csR4B);
				theResponseDetails.setResponseResource(VersionConvertorFactory_43_50.convertResource(this.getTerminologyCapabilities(csR5)));
			} else {
				throw new MatchboxUnsupportedFhirVersionException("TerminologyCapabilitiesInterceptor",
																				  theRequestDetails.getFhirContext().getVersion().getVersion());
			}
		}
		return true;
	}

	private TerminologyCapabilities getTerminologyCapabilities(final CapabilityStatement cs) {
		final var tc = new TerminologyCapabilities();
		tc.setId(cs.getId());
		tc.setUrl(cs.getUrl());
		tc.setVersion(cs.getVersion());
		tc.setName(cs.getName());
		tc.setTitle(cs.getTitle());
		tc.setStatus(cs.getStatus());
		tc.setExperimental(cs.getExperimental());
		tc.setDate(cs.getDate());
		tc.setPublisher(cs.getPublisher());
		tc.setDescription(cs.getDescription());

		for (final var codeSystem : this.getCodeSystems()) {
			tc.getCodeSystem().add(new TerminologyCapabilities.TerminologyCapabilitiesCodeSystemComponent().setUri(codeSystem));
		}

		return tc;
	}

	private List<String> getCodeSystems() {
		return List.of(SNOMED_SCT_CODESYSTEM_URL, RXNORM_CODESYSTEM_URL, LOINC_CODESYSTEM_URL, UCUM_CODESYSTEM_URL,
							HL7_TERMINOLOGY_CODESYSTEM_BASE_URL, HL7_SID_CODESYSTEM_BASE_URL, HL7_FHIR_CODESYSTEM_BASE_URL,
							LANG_CODESYSTEM_URN, MIMETYPES_CODESYSTEM_URN, _11073_CODESYSTEM_URN,
							DICOM_CODESYSTEM_URL,
							"http://fdasis.nlm.nih.gov", "http://hl7.org/fhir/sid/ndc",
							"http://unstats.un.org/unsd/methods/m49/m49.htm", "http://varnomen.hgvs.org",
							"https://www.usps.com/", "urn:ietf:rfc:3986", "urn:iso:std:iso:3166",
							"urn:iso:std:iso:4217", "urn:oid:1.2.36.1.2001.1005.17");
	}
}
