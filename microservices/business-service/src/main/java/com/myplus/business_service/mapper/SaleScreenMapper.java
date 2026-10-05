package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;

/**
 * MS-4 — the sale screens (was the MM-1 STRICT saleDisplay profile): a sale line, its invoice header and the buyer.
 * The three methods compose — a Sell's customerHistory, and that header's customer, map through the others.
 * The Customer method is deliberately NOT CustomerMapper's: the sale screens matched STRICTLY, the Customer screens did
 * not, and MapStructOracleTest holds each to its own oracle.
 */
@Mapper
public interface SaleScreenMapper extends DisplayDates {

    @Mapping(target = "autoRate", ignore = true)      // PR-3c: a till input, never read back
    @Mapping(target = "batches", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "cc", ignore = true)
    @Mapping(target = "cn", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "customerId", ignore = true)
    @Mapping(target = "customerType", ignore = true)
    @Mapping(target = "dueAmount", ignore = true)
    @Mapping(target = "dueDate", ignore = true)
    @Mapping(target = "due_days", ignore = true)
    @Mapping(target = "ed", ignore = true)
    @Mapping(target = "grandTotal", ignore = true)
    @Mapping(target = "groupBy", ignore = true)
    @Mapping(target = "invoiceNo", ignore = true)
    @Mapping(target = "itemCode", ignore = true)
    @Mapping(target = "itemName", ignore = true)
    @Mapping(target = "itemStock", ignore = true)
    @Mapping(target = "looseUnit", ignore = true)
    @Mapping(target = "looseUnitPlural", ignore = true)
    @Mapping(target = "manufacturer", ignore = true)
    @Mapping(target = "orderType", ignore = true)
    @Mapping(target = "packing", ignore = true)
    @Mapping(target = "paymentMode", ignore = true)
    @Mapping(target = "rp", ignore = true)
    @Mapping(target = "sd", ignore = true)
    @Mapping(target = "sellSId", ignore = true)
    @Mapping(target = "serials", ignore = true)
    @Mapping(target = "stock", ignore = true)
    @Mapping(target = "stockEntryId", ignore = true)  // PR-3c: a till input; the sale's batches are in sell_batch
    SellDTO toDto(Sell sell);

    @Mapping(target = "autoCut", ignore = true)
    @Mapping(target = "cashDrawer", ignore = true)
    @Mapping(target = "creditAcknowledged", ignore = true)
    @Mapping(target = "currencyFraction", ignore = true)
    @Mapping(target = "currencySymbol", ignore = true)
    @Mapping(target = "currencyWord", ignore = true)
    @Mapping(target = "documentProfile", ignore = true)
    @Mapping(target = "fiscalLine", ignore = true)
    @Mapping(target = "fontFamily", ignore = true)
    @Mapping(target = "footerText", ignore = true)
    @Mapping(target = "installmentPlan", ignore = true)
    @Mapping(target = "layoutMode", ignore = true)
    @Mapping(target = "letterhead", ignore = true)
    @Mapping(target = "numberSystem", ignore = true)
    @Mapping(target = "paperWidthDots", ignore = true)
    @Mapping(target = "prescriptionId", ignore = true)
    @Mapping(target = "printAgentUrl", ignore = true)
    @Mapping(target = "printMode", ignore = true)
    @Mapping(target = "printTransport", ignore = true)
    @Mapping(target = "qrDataUri", ignore = true)
    @Mapping(target = "qrPayload", ignore = true)
    @Mapping(target = "qtyDecimals", ignore = true)
    @Mapping(target = "receivedAmount", ignore = true)
    @Mapping(target = "sales", ignore = true)
    @Mapping(target = "serialsClaimed", ignore = true)
    @Mapping(target = "showAmountInWords", ignore = true)
    @Mapping(target = "showPromo", ignore = true)
    @Mapping(target = "showTaxBreakdown", ignore = true)
    @Mapping(target = "storeCreditApplied", ignore = true)
    @Mapping(target = "taxLabel", ignore = true)
    @Mapping(target = "taxRegNo", ignore = true)
    @Mapping(target = "tenders", ignore = true)
    @Mapping(target = "termsText", ignore = true)
    @Mapping(target = "warnings", ignore = true)
    CustomerHistoryDTO toDto(CustomerHistory header);

    @Mapping(target = "alsoSupplier", ignore = true)
    @Mapping(target = "customerHistory", ignore = true)
    @Mapping(target = "paidAmount", ignore = true)
    CustomerDTO toDto(Customer buyer);
}
